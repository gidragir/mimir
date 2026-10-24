package dev.mimir.drive.auth

import dev.mimir.drive.client.AuthRevokedException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoogleAuthClientTest {
    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(1700000000)
    }

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
}
