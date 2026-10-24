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
}
