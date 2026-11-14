package dev.mimir.drive.auth

import dev.mimir.drive.client.AuthRevokedException
import dev.mimir.drive.client.DriveAuthException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import dev.mimir.core.testing.TestClock
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GoogleAuthClientTest {
    private val fixedClock = TestClock(Instant.fromEpochSeconds(1700000000))

    @Test
    fun testRefreshTokensSuccess() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals("https://oauth2.googleapis.com/token", request.url.toString())
            respond(
                content = """{"access_token":"new_access","expires_in":3600,"token_type":"Bearer"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val authClient = GoogleAuthClient(client, GoogleClientCredentials(GoogleClientKind.Device, "test-client-id", null), clock = fixedClock)
        val refreshed = authClient.refreshTokens("old_refresh")
        assertEquals("new_access", refreshed.accessToken)
        assertEquals("old_refresh", refreshed.refreshToken)
        assertEquals(1700000000L + 3600L, refreshed.expiresAtEpochSeconds)
    }

    @Test
    fun testDeviceCodeFlowRequest() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals("https://oauth2.googleapis.com/device/code", request.url.toString())
            respond(
                content = """
                    {
                        "device_code": "dev123",
                        "user_code": "ABCD-EFGH",
                        "verification_url": "https://www.google.com/device",
                        "expires_in": 1800,
                        "interval": 5
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val authClient = GoogleAuthClient(client, GoogleClientCredentials(GoogleClientKind.Device, "test-client-id", null), clock = fixedClock)
        val devCode = authClient.requestDeviceCode()
        assertEquals("dev123", devCode.device_code)
        assertEquals("ABCD-EFGH", devCode.user_code)
    }

    @Test
    fun testRefreshTokensInvalidGrantThrowsAuthRevokedException() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = """{"error": "invalid_grant", "error_description": "Token has been expired or revoked."}""",
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val authClient = GoogleAuthClient(client, GoogleClientCredentials(GoogleClientKind.Device, "test-client-id", null), clock = fixedClock)
        assertFailsWith<AuthRevokedException> {
            authClient.refreshTokens("revoked_refresh")
        }
    }

    @Test
    fun fromIdsBuildsClientsAndSkipsBlankIds() {
        val client = HttpClient(MockEngine { respond("") })
        val both = GoogleAuthClients.fromIds(
            http = client,
            desktopId = "desktop-id",
            desktopSecret = "desktop-secret",
            deviceId = "device-id",
            deviceSecret = "",
        )
        val desktop = assertNotNull(both.desktop)
        val device = assertNotNull(both.device)
        assertEquals(GoogleClientKind.Desktop, desktop.kind)
        assertEquals(GoogleClientKind.Device, device.kind)

        val empty = GoogleAuthClients.fromIds(
            http = client,
            desktopId = " ",
            desktopSecret = "sec",
            deviceId = null,
            deviceSecret = null,
        )
        assertNull(empty.desktop)
        assertNull(empty.device)
    }

    @Test
    fun toDevicePollStepMapsRfc8628Errors() {
        val bad = HttpStatusCode.BadRequest
        assertEquals(DevicePollStep.Pending, DriveAuthException(bad, """{"error":"authorization_pending"}""").toDevicePollStep())
        assertEquals(DevicePollStep.SlowDown, DriveAuthException(bad, """{"error":"slow_down"}""").toDevicePollStep())
        assertEquals(DevicePollStep.Expired, DriveAuthException(bad, """{"error":"expired_token"}""").toDevicePollStep())
        assertEquals(DevicePollStep.Denied, DriveAuthException(bad, """{"error":"access_denied"}""").toDevicePollStep())
        assertNull(DriveAuthException(bad, """{"error":"invalid_client"}""").toDevicePollStep())
    }

    @Test
    fun deviceCodeFlowRunnerHandlesPendingSlowDownAndAuthorized() = runTest {
        var polls = 0
        val mockEngine = MockEngine { request ->
            val headers = headersOf(HttpHeaders.ContentType, "application/json")
            when (request.url.toString()) {
                "https://oauth2.googleapis.com/device/code" -> respond(
                    content = """{"device_code":"dev1","user_code":"AB-CD","verification_url":"https://google.com/device","expires_in":120,"interval":5}""",
                    status = HttpStatusCode.OK,
                    headers = headers,
                )
                "https://oauth2.googleapis.com/token" -> when (++polls) {
                    1 -> respond("""{"error":"authorization_pending"}""", HttpStatusCode.BadRequest, headers)
                    2 -> respond("""{"error":"slow_down"}""", HttpStatusCode.BadRequest, headers)
                    else -> respond("""{"access_token":"acc","refresh_token":"ref","expires_in":3600}""", HttpStatusCode.OK, headers)
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val http = HttpClient(mockEngine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val authClient = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "cid", null), clock = fixedClock)
        var shownCode: String? = null

        val result = DeviceCodeFlowRunner(authClient, fixedClock).run { shownCode = it.user_code }

        assertEquals("AB-CD", shownCode)
        val authorized = assertIs<DeviceCodeFlowRunner.Result.Authorized>(result)
        assertEquals("acc", authorized.tokens.accessToken)
        assertEquals(20_000L, currentTime) // 5s (pending) + 5s (slow_down) + 10s (authorized)
    }

    @Test
    fun deviceCodeFlowRunnerReturnsDeniedAndExpired() = runTest {
        fun runnerFor(errorBody: String): DeviceCodeFlowRunner {
            val mockEngine = MockEngine { request ->
                val headers = headersOf(HttpHeaders.ContentType, "application/json")
                if (request.url.toString().endsWith("/device/code")) {
                    respond(
                        """{"device_code":"dev1","user_code":"AB-CD","verification_url":"https://google.com/device","expires_in":10,"interval":5}""",
                        HttpStatusCode.OK,
                        headers,
                    )
                } else {
                    respond(errorBody, HttpStatusCode.BadRequest, headers)
                }
            }
            val http = HttpClient(mockEngine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            return DeviceCodeFlowRunner(
                GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "cid", null), clock = fixedClock),
                fixedClock,
            )
        }

        assertIs<DeviceCodeFlowRunner.Result.Denied>(runnerFor("""{"error":"access_denied"}""").run {})
        assertEquals(DeviceCodeFlowRunner.Result.Expired, runnerFor("""{"error":"expired_token"}""").run {})
        assertEquals(DeviceCodeFlowRunner.Result.Expired, runnerFor("""{"error":"authorization_pending"}""").run {})
    }
}
