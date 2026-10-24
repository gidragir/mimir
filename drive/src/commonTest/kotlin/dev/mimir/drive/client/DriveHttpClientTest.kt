package dev.mimir.drive.client

import dev.mimir.drive.auth.GoogleAuthClient
import dev.mimir.drive.auth.GoogleAuthClients
import dev.mimir.drive.auth.GoogleClientCredentials
import dev.mimir.drive.auth.GoogleClientKind
import dev.mimir.drive.auth.InMemoryTokenStorage
import dev.mimir.drive.auth.OAuthTokens
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import dev.mimir.core.RemoteAuthException
import dev.mimir.core.RemoteUnavailableException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DriveHttpClientTest {
    @Test
    fun testRetriesWithRefreshedTokenOn401() = runTest {
        var callCount = 0
        val mockEngine = MockEngine { request ->
            if (request.url.encodedPath == "/oauth2/v3/token" || request.url.encodedPath == "/token") {
                respond(
                    content = """{"access_token":"refreshed_access","expires_in":3600,"token_type":"Bearer"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                callCount++
                val authHeader = request.headers[HttpHeaders.Authorization]
                if (authHeader == "Bearer initial_access") {
                    respond("Unauthorized", status = HttpStatusCode.Unauthorized)
                } else if (authHeader == "Bearer refreshed_access") {
                    respond("""{"status":"ok"}""", status = HttpStatusCode.OK)
                } else {
                    respond("Bad token", status = HttpStatusCode.Forbidden)
                }
            }
        }

        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val authClient = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "client-id", null))
        val storage = InMemoryTokenStorage(
            OAuthTokens(
                accessToken = "initial_access",
                refreshToken = "valid_refresh",
                expiresAtEpochSeconds = 2000000000L
            )
        )
        val driveClient = DriveHttpClient(http, storage, GoogleAuthClients(null, authClient))

        val response = driveClient.executeWithRetry { token ->
            http.get("https://www.googleapis.com/drive/v3/about") {
                headers[HttpHeaders.Authorization] = "Bearer $token"
            }
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(2, callCount)
        assertEquals("refreshed_access", storage.load()?.accessToken)
    }

    @Test
    fun testRetriesOn429TooManyRequests() = runTest {
        var attempts = 0
        val mockEngine = MockEngine { request ->
            attempts++
            if (attempts < 3) {
                respond("Too Many Requests", status = HttpStatusCode.TooManyRequests)
            } else {
                respond("""{"status":"ok"}""", status = HttpStatusCode.OK)
            }
        }

        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val authClient = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "client-id", null))
        val storage = InMemoryTokenStorage(OAuthTokens("valid_token", "refresh", 2000000000L))
        val driveClient = DriveHttpClient(http, storage, GoogleAuthClients(null, authClient))

        val response = driveClient.executeWithRetry(maxRetries = 3) { token ->
            http.get("https://www.googleapis.com/drive/v3/about") {
                headers[HttpHeaders.Authorization] = "Bearer $token"
            }
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(3, attempts)
    }

    private fun clientFor(engine: MockEngine, tokens: OAuthTokens?): DriveHttpClient {
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val auth = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "client-id", null))
        return DriveHttpClient(http, InMemoryTokenStorage(tokens), GoogleAuthClients(null, auth))
    }

    private val validTokens = OAuthTokens("valid_token", "refresh", 2000000000L)

    private suspend fun DriveHttpClient.callAbout() = executeWithRetry { token ->
        httpClient.get("https://www.googleapis.com/drive/v3/about") {
            headers[HttpHeaders.Authorization] = "Bearer $token"
        }
    }

    @Test
    fun ioExceptionBecomesRemoteUnavailable() = runTest {
        val c = clientFor(MockEngine { throw kotlinx.io.IOException("connection reset") }, validTokens)
        assertFailsWith<RemoteUnavailableException> { c.callAbout() }
    }

    @Test
    fun timeoutBecomesRemoteUnavailable() = runTest {
        val c = clientFor(
            MockEngine { throw HttpRequestTimeoutException("https://www.googleapis.com/drive/v3/about", 1000L) },
            validTokens,
        )
        assertFailsWith<RemoteUnavailableException> { c.callAbout() }
    }

    @Test
    fun cancellationIsNotWrapped() = runTest {
        val c = clientFor(MockEngine { throw kotlinx.coroutines.CancellationException("cancelled") }, validTokens)
        assertFailsWith<kotlinx.coroutines.CancellationException> { c.callAbout() }
    }

    @Test
    fun persistent503BecomesRemoteUnavailable() = runTest {
        var attempts = 0
        val c = clientFor(
            MockEngine { attempts++; respond("down", status = HttpStatusCode.ServiceUnavailable) },
            validTokens,
        )
        assertFailsWith<RemoteUnavailableException> { c.callAbout() }
        assertEquals(3, attempts)
    }

    @Test
    fun revokedRefreshBecomesRemoteAuth() = runTest {
        val c = clientFor(
            MockEngine {
                respond(
                    """{"error":"invalid_grant"}""",
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
            OAuthTokens("old", "refresh", 1L),
        )
        assertFailsWith<RemoteAuthException> { c.getValidToken() }
        assertFailsWith<RemoteAuthException> { c.forceRefreshToken() }
    }

    @Test
    fun missingTokensBecomeRemoteAuth() = runTest {
        val c = clientFor(MockEngine { respond("{}") }, null)
        assertFailsWith<RemoteAuthException> { c.getValidToken() }
        assertFailsWith<RemoteAuthException> { c.callAbout() }
    }

    private suspend fun assertPersistent5xx(status: HttpStatusCode) {
        var attempts = 0
        val c = clientFor(MockEngine { attempts++; respond("err", status = status) }, validTokens)
        assertFailsWith<RemoteUnavailableException> { c.callAbout() }
        assertEquals(3, attempts)
    }

    @Test
    fun persistent500BecomesRemoteUnavailable() = runTest { assertPersistent5xx(HttpStatusCode.InternalServerError) }

    @Test
    fun persistent502BecomesRemoteUnavailable() = runTest { assertPersistent5xx(HttpStatusCode.BadGateway) }

    @Test
    fun persistent504BecomesRemoteUnavailable() = runTest { assertPersistent5xx(HttpStatusCode.GatewayTimeout) }

    @Test
    fun persistent429PassesThrough() = runTest {
        var attempts = 0
        val c = clientFor(MockEngine { attempts++; respond("slow", status = HttpStatusCode.TooManyRequests) }, validTokens)
        assertEquals(HttpStatusCode.TooManyRequests, c.callAbout().status)
        assertEquals(3, attempts)
    }

    @Test
    fun tokenEndpointOutageBecomesRemoteUnavailable() = runTest {
        val c = clientFor(
            MockEngine { respond("down", status = HttpStatusCode.ServiceUnavailable) },
            OAuthTokens("old", "refresh", 1L),
        )
        assertFailsWith<RemoteUnavailableException> { c.getValidToken() }
        assertFailsWith<RemoteUnavailableException> { c.forceRefreshToken() }
    }
}
