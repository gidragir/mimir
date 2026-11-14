package dev.mimir.drive.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import dev.mimir.core.testing.TestClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class RefreshingTokenSourceTest : AccessTokenSourceContract() {
    private val now = Instant.parse("2026-10-24T12:00:00Z")
    private val clock = TestClock(now)
    private var tokenPosts = 0

    private fun clients(): GoogleAuthClients {
        val engine = MockEngine {
            tokenPosts++
            respond(
                """{"access_token":"new_access","expires_in":3600,"token_type":"Bearer"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        return GoogleAuthClients(null, GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "client-id", null)))
    }

    private fun source(tokens: OAuthTokens?) = RefreshingTokenSource(InMemoryTokenStorage(tokens), clients(), clock)

    override val storedToken = "stored_access"
    override fun signedIn(): AccessTokenSource =
        source(OAuthTokens(storedToken, "refresh", now.epochSeconds + 3600))
    override fun signedOut(): AccessTokenSource = source(null)

    @Test
    fun `expired token is refreshed with a single POST`() = runTest {
        val source = source(OAuthTokens("old", "refresh", now.epochSeconds - 10))
        assertEquals("new_access", source.token())
        assertEquals("new_access", source.token())
        assertEquals(1, tokenPosts)
    }
}
