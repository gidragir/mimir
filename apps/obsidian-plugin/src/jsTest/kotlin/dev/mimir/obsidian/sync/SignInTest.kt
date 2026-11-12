package dev.mimir.obsidian.sync

import dev.mimir.drive.auth.GoogleAuthClient
import dev.mimir.drive.auth.GoogleClientCredentials
import dev.mimir.drive.auth.GoogleClientKind
import dev.mimir.drive.auth.OAuthTokens
import dev.mimir.obsidian.fakes.FakeLocalStorage
import dev.mimir.obsidian.fakes.FakeVaultFs
import dev.mimir.obsidian.state.DeviceSecretStore
import dev.mimir.obsidian.state.ObsidianSyncStateStore
import dev.mimir.obsidian.state.PluginSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SignInTest {

    private class TestClock(var now: Instant = Instant.fromEpochSeconds(1700000000L)) : Clock {
        override fun now(): Instant = now
    }

    private fun mockHttpClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient {
        val mockEngine = MockEngine(handler)
        return HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    @Test
    fun noClientConfiguredReturnsNull() {
        val http = mockHttpClient { respond("") }
        val settings = PluginSettings(customClientId = "", customClientSecret = "")
        // PluginBuildConfig has "" when no secrets in build
        val client = deviceAuthClient(http, settings, defaultClientId = "", defaultClientSecret = "")
        assertNull(client)
    }

    @Test
    fun happyPathSavesTokensAndEmail() = runTest {
        val clock = TestClock()
        val http = mockHttpClient { request ->
            when (request.url.toString()) {
                "https://oauth2.googleapis.com/device/code" -> {
                    respond(
                        content = """{"device_code":"dev123","user_code":"USER-123","verification_url":"https://google.com/device","expires_in":1800,"interval":1}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                "https://oauth2.googleapis.com/token" -> {
                    respond(
                        content = """{"access_token":"at-123","refresh_token":"rt-123","expires_in":3600,"token_type":"Bearer"}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                "https://www.googleapis.com/oauth2/v3/userinfo" -> {
                    respond(
                        content = """{"email":"test@example.com","name":"Test User"}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val authClient = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "test-client", null), clock = clock)
        val storage = FakeLocalStorage()
        val secrets = DeviceSecretStore(storage)
        val signIn = DeviceSignIn(authClient, secrets, clock = clock)

        val job = launch { signIn.run() }
        val awaiting = assertIs<SignInState.AwaitingUser>(
            signIn.state.first { it is SignInState.AwaitingUser }
        )
        assertEquals("USER-123", awaiting.userCode)
        assertEquals("https://google.com/device", awaiting.verificationUrl)

        advanceTimeBy(1_000)
        job.join()

        val success = assertIs<SignInState.Success>(signIn.state.value)
        assertEquals("test@example.com", success.email)
        assertEquals("at-123", secrets.load()?.accessToken)
        assertEquals("rt-123", secrets.load()?.refreshToken)
    }

    @Test
    fun pollsWithServerInterval() = runTest {
        val clock = TestClock()
        var pollCount = 0
        val http = mockHttpClient { request ->
            when (request.url.toString()) {
                "https://oauth2.googleapis.com/device/code" -> {
                    respond(
                        content = """{"device_code":"dev123","user_code":"USER-123","verification_url":"https://google.com/device","expires_in":1800,"interval":7}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                "https://oauth2.googleapis.com/token" -> {
                    pollCount++
                    if (pollCount == 1) {
                        respond(
                            content = """{"error":"authorization_pending"}""",
                            status = HttpStatusCode.BadRequest,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            content = """{"access_token":"at-success","expires_in":3600,"token_type":"Bearer"}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }
                "https://www.googleapis.com/oauth2/v3/userinfo" -> {
                    respond(
                        content = """{"email":"poll@example.com"}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val authClient = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "test-client", null), clock = clock)
        val storage = FakeLocalStorage()
        val secrets = DeviceSecretStore(storage)
        val signIn = DeviceSignIn(authClient, secrets, clock = clock)

        val job = launch { signIn.run() }
        assertIs<SignInState.AwaitingUser>(signIn.state.first { it is SignInState.AwaitingUser })

        // After 6s, no poll yet
        advanceTimeBy(6_000)
        yield()
        assertEquals(0, pollCount)

        // After 7s total, first poll occurs (authorization_pending)
        advanceTimeBy(1_000)
        yield()
        yield()
        assertEquals(1, pollCount)
        assertIs<SignInState.AwaitingUser>(signIn.state.value)

        // After another 7s, second poll succeeds
        advanceTimeBy(7_000)
        yield()
        yield()
        assertEquals(2, pollCount)
        job.join()

        assertIs<SignInState.Success>(signIn.state.value)
    }

    @Test
    fun expiredCodeGivesExpired() = runTest {
        val clock = TestClock()
        val http = mockHttpClient { request ->
            when (request.url.toString()) {
                "https://oauth2.googleapis.com/device/code" -> {
                    respond(
                        content = """{"device_code":"dev123","user_code":"USER-123","verification_url":"https://google.com/device","expires_in":1800,"interval":2}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                "https://oauth2.googleapis.com/token" -> {
                    respond(
                        content = """{"error":"expired_token"}""",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val authClient = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "test-client", null), clock = clock)
        val storage = FakeLocalStorage()
        val secrets = DeviceSecretStore(storage)
        val signIn = DeviceSignIn(authClient, secrets, clock = clock)

        val job = launch { signIn.run() }
        assertIs<SignInState.AwaitingUser>(signIn.state.first { it is SignInState.AwaitingUser })

        advanceTimeBy(2_000)
        job.join()

        assertIs<SignInState.Expired>(signIn.state.value)
    }

    @Test
    fun accessDeniedGivesFailed() = runTest {
        val clock = TestClock()
        val http = mockHttpClient { request ->
            when (request.url.toString()) {
                "https://oauth2.googleapis.com/device/code" -> {
                    respond(
                        content = """{"device_code":"dev123","user_code":"USER-123","verification_url":"https://google.com/device","expires_in":1800,"interval":2}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                "https://oauth2.googleapis.com/token" -> {
                    respond(
                        content = """{"error":"access_denied"}""",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val authClient = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "test-client", null), clock = clock)
        val storage = FakeLocalStorage()
        val secrets = DeviceSecretStore(storage)
        val signIn = DeviceSignIn(authClient, secrets, clock = clock)

        val job = launch { signIn.run() }
        assertIs<SignInState.AwaitingUser>(signIn.state.first { it is SignInState.AwaitingUser })

        advanceTimeBy(2_000)
        job.join()

        val failed = assertIs<SignInState.Failed>(signIn.state.value)
        assertTrue(failed.message.contains("Access denied", ignoreCase = true))
    }

    @Test
    fun networkErrorGivesOffline() = runTest {
        val clock = TestClock()
        val http = mockHttpClient { _ ->
            throw IOException("Network is down")
        }

        val authClient = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "test-client", null), clock = clock)
        val storage = FakeLocalStorage()
        val secrets = DeviceSecretStore(storage)
        val signIn = DeviceSignIn(authClient, secrets, clock = clock)

        signIn.run()

        assertIs<SignInState.Offline>(signIn.state.value)
    }

    @Test
    fun signOutClearsEvenIfRevokeFails() = runTest {
        var revokeCalled = false
        val http = mockHttpClient { request ->
            if (request.url.toString() == "https://oauth2.googleapis.com/revoke") {
                revokeCalled = true
                respondError(HttpStatusCode.InternalServerError)
            } else {
                respondError(HttpStatusCode.NotFound)
            }
        }

        val authClient = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "test-client", null))
        val storage = FakeLocalStorage()
        val secrets = DeviceSecretStore(storage)
        secrets.save(OAuthTokens("access-tok", "refresh-tok", 2000000000L))
        secrets.saveCanaryHash("canary")
        secrets.saveChangeToken("change-tok")

        val fs = FakeVaultFs()
        val stateStore = ObsidianSyncStateStore(fs, ".obsidian/plugins/mimir-sync")

        signOut(authClient, secrets, stateStore)

        assertTrue(revokeCalled)
        assertNull(secrets.load())
        assertNull(secrets.loadKey())
        assertNull(secrets.loadCanaryHash())
        assertNull(secrets.loadChangeToken())
        assertEquals(0, stateStore.load().records.size)
    }
}
