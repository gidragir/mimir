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
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.test.TestScope
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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

        val response = driveClient.executeWithRetry { token ->
            http.get("https://www.googleapis.com/drive/v3/about") {
                headers[HttpHeaders.Authorization] = "Bearer $token"
            }
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(3, attempts)
    }

    private fun clientFor(
        engine: MockEngine,
        tokens: OAuthTokens?,
        clock: Clock = Clock.System,
        retryPolicy: RetryPolicy = RetryPolicy(),
        random: Random = Random.Default,
    ): DriveHttpClient {
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val auth = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "client-id", null))
        return DriveHttpClient(http, InMemoryTokenStorage(tokens), GoogleAuthClients(null, auth), clock, retryPolicy, random)
    }

    private val fixedNow = Instant.parse("2026-10-24T12:00:00Z")
    private val fixedClock = object : Clock {
        override fun now(): Instant = fixedNow
    }

    /** Вызов `about`, фиксирующий виртуальное время (мс) перед каждой попыткой. */
    private suspend fun TestScope.timedCall(c: DriveHttpClient, times: MutableList<Long>) = c.executeWithRetry { token ->
        times += testScheduler.currentTime
        c.httpClient.get("https://www.googleapis.com/drive/v3/about") {
            headers[HttpHeaders.Authorization] = "Bearer $token"
        }
    }

    private fun gaps(times: List<Long>) = times.zipWithNext { a, b -> b - a }

    private fun rateLimit403Body(reason: String) =
        """{"error":{"code":403,"message":"x","errors":[{"domain":"usageLimits","reason":"$reason","message":"x"}]}}"""

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
    fun dnsFailureBecomesRemoteUnavailable() = runTest {
        val c = clientFor(MockEngine { throw UnresolvedAddressException() }, validTokens)
        assertFailsWith<RemoteUnavailableException> { c.callAbout() }
    }

    @Test
    fun dnsFailureOnTokenRefreshBecomesRemoteUnavailable() = runTest {
        val c = clientFor(MockEngine { throw UnresolvedAddressException() }, OAuthTokens("old", "refresh", 1L))
        assertFailsWith<RemoteUnavailableException> { c.getValidToken() }
        assertFailsWith<RemoteUnavailableException> { c.forceRefreshToken() }
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
        assertEquals(5, attempts)
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
        assertEquals(5, attempts)
    }

    @Test
    fun persistent500BecomesRemoteUnavailable() = runTest { assertPersistent5xx(HttpStatusCode.InternalServerError) }

    @Test
    fun persistent502BecomesRemoteUnavailable() = runTest { assertPersistent5xx(HttpStatusCode.BadGateway) }

    @Test
    fun persistent504BecomesRemoteUnavailable() = runTest { assertPersistent5xx(HttpStatusCode.GatewayTimeout) }


    private fun retryAfterEngine(status: HttpStatusCode, value: String, attempts: IntArray = IntArray(1)) = MockEngine {
        attempts[0]++
        if (attempts[0] == 1) {
            respond("slow", status = status, headers = headersOf(HttpHeaders.RetryAfter, value))
        } else {
            respond("""{"status":"ok"}""", status = HttpStatusCode.OK)
        }
    }

    @Test
    fun retriesOn429HonouringRetryAfterSeconds() = runTest {
        val times = mutableListOf<Long>()
        val c = clientFor(retryAfterEngine(HttpStatusCode.TooManyRequests, "7"), validTokens)
        assertEquals(HttpStatusCode.OK, timedCall(c, times).status)
        assertEquals(listOf(7_000L), gaps(times))
    }

    @Test
    fun retryAfterHttpDateIsRelativeToClock() = runTest {
        val times = mutableListOf<Long>()
        // 12:00:00Z + 9 с
        val c = clientFor(
            retryAfterEngine(HttpStatusCode.ServiceUnavailable, "Sat, 24 Oct 2026 12:00:09 GMT"),
            validTokens,
            clock = fixedClock,
        )
        assertEquals(HttpStatusCode.OK, timedCall(c, times).status)
        assertEquals(listOf(9_000L), gaps(times))
    }

    @Test
    fun retryAfterAboveMaxDelayIsCapped() = runTest {
        val times = mutableListOf<Long>()
        val c = clientFor(retryAfterEngine(HttpStatusCode.TooManyRequests, "120"), validTokens)
        assertEquals(HttpStatusCode.OK, timedCall(c, times).status)
        assertEquals(listOf(32_000L), gaps(times))
    }

    @Test
    fun retryAfterInPastMeansNoWait() = runTest {
        val times = mutableListOf<Long>()
        val c = clientFor(
            retryAfterEngine(HttpStatusCode.TooManyRequests, "Sat, 24 Oct 2026 11:00:00 GMT"),
            validTokens,
            clock = fixedClock,
        )
        assertEquals(HttpStatusCode.OK, timedCall(c, times).status)
        assertEquals(listOf(0L), gaps(times))
    }

    @Test
    fun unparsableRetryAfterFallsBackToBackoff() = runTest {
        val times = mutableListOf<Long>()
        val c = clientFor(retryAfterEngine(HttpStatusCode.TooManyRequests, "soon"), validTokens, random = Random(1))
        assertEquals(HttpStatusCode.OK, timedCall(c, times).status)
        assertTrue(gaps(times).single() in 500L..1000L)
    }

    @Test
    fun retriesOn403UserRateLimitExceeded() = runTest {
        var attempts = 0
        val c = clientFor(
            MockEngine {
                attempts++
                if (attempts == 1) {
                    respond(rateLimit403Body("userRateLimitExceeded"), status = HttpStatusCode.Forbidden)
                } else {
                    respond("""{"status":"ok"}""", status = HttpStatusCode.OK)
                }
            },
            validTokens,
        )
        assertEquals(HttpStatusCode.OK, c.callAbout().status)
        assertEquals(2, attempts)
    }

    @Test
    fun retriesOn403RateLimitExceeded() = runTest {
        var attempts = 0
        val c = clientFor(
            MockEngine {
                attempts++
                if (attempts == 1) {
                    respond(rateLimit403Body("rateLimitExceeded"), status = HttpStatusCode.Forbidden)
                } else {
                    respond("""{"status":"ok"}""", status = HttpStatusCode.OK)
                }
            },
            validTokens,
        )
        assertEquals(HttpStatusCode.OK, c.callAbout().status)
        assertEquals(2, attempts)
    }

    @Test
    fun doesNotRetry403InsufficientPermissions() = runTest {
        var attempts = 0
        val body = rateLimit403Body("insufficientPermissions")
        val c = clientFor(MockEngine { attempts++; respond(body, status = HttpStatusCode.Forbidden) }, validTokens)
        val response = c.callAbout()
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(body, response.bodyAsText())
        assertEquals(1, attempts)
    }

    @Test
    fun doesNotRetry403WithUnparsableBody() = runTest {
        var attempts = 0
        val c = clientFor(MockEngine { attempts++; respond("<html>no</html>", status = HttpStatusCode.Forbidden) }, validTokens)
        assertEquals(HttpStatusCode.Forbidden, c.callAbout().status)
        assertEquals(1, attempts)
    }

    @Test
    fun backoffIsJitteredWithinBounds() = runTest {
        val times = mutableListOf<Long>()
        val c = clientFor(
            MockEngine { respond("down", status = HttpStatusCode.ServiceUnavailable) },
            validTokens,
            random = Random(42),
        )
        assertFailsWith<RemoteUnavailableException> { timedCall(c, times) }
        val waits = gaps(times)
        assertEquals(4, waits.size)
        listOf(1_000L, 2_000L, 4_000L, 8_000L).zip(waits).forEach { (d, w) ->
            assertTrue(w in d / 2..d, "wait $w not in [${d / 2}, $d]")
        }
        assertTrue(waits.distinct().size == waits.size)
    }

    @Test
    fun backoffIsReproducibleWithSameSeed() {
        val p = RetryPolicy()
        assertEquals(p.backoff(3, Random(7)), p.backoff(3, Random(7)))
    }

    @Test
    fun backoffIsCappedByMaxDelay() {
        val p = RetryPolicy()
        repeat(50) { i ->
            val d = p.backoff(40, Random(i))
            assertTrue(d in 16.seconds..32.seconds, "capped backoff $d")
        }
    }

    @Test
    fun parseRetryAfterHandlesSecondsDatesAndGarbage() {
        assertEquals(null, parseRetryAfter(null, fixedNow))
        assertEquals(7.seconds, parseRetryAfter("7", fixedNow))
        assertEquals(7.seconds, parseRetryAfter(" 7 ", fixedNow))
        assertEquals(0.seconds, parseRetryAfter("0", fixedNow))
        assertEquals(null, parseRetryAfter("-3", fixedNow))
        assertEquals(null, parseRetryAfter("soon", fixedNow))
        assertEquals(9.seconds, parseRetryAfter("Sat, 24 Oct 2026 12:00:09 GMT", fixedNow))
        assertEquals(0.seconds, parseRetryAfter("Sat, 24 Oct 2026 11:00:00 GMT", fixedNow))
    }

    @Test
    fun exhausted429ThrowsRemoteUnavailable() = runTest {
        var attempts = 0
        val c = clientFor(MockEngine { attempts++; respond("slow", status = HttpStatusCode.TooManyRequests) }, validTokens)
        val e = assertFailsWith<RemoteUnavailableException> { c.callAbout() }
        assertEquals(5, attempts)
        assertTrue("429" in e.message.orEmpty(), e.message)
    }

    @Test
    fun exhausted403RateLimitThrowsRemoteUnavailable() = runTest {
        var attempts = 0
        val c = clientFor(
            MockEngine { attempts++; respond(rateLimit403Body("rateLimitExceeded"), status = HttpStatusCode.Forbidden) },
            validTokens,
        )
        val e = assertFailsWith<RemoteUnavailableException> { c.callAbout() }
        assertEquals(5, attempts)
        assertTrue("rate limit" in e.message.orEmpty(), e.message)
    }

    @Test
    fun exhausted5xxThrowsRemoteUnavailable() = runTest {
        var attempts = 0
        val c = clientFor(MockEngine { attempts++; respond("down", status = HttpStatusCode.ServiceUnavailable) }, validTokens)
        val e = assertFailsWith<RemoteUnavailableException> { c.callAbout() }
        assertEquals(5, attempts)
        assertTrue("503" in e.message.orEmpty(), e.message)
    }

    @Test
    fun maxAttemptsComesFromPolicy() = runTest {
        var attempts = 0
        val c = clientFor(
            MockEngine { attempts++; respond("down", status = HttpStatusCode.ServiceUnavailable) },
            validTokens,
            retryPolicy = RetryPolicy(maxAttempts = 2),
        )
        assertFailsWith<RemoteUnavailableException> { c.callAbout() }
        assertEquals(2, attempts)
    }

    @Test
    fun refreshOn401DoesNotConsumeAttempts() = runTest {
        var attempts = 0
        val c = clientFor(
            MockEngine { request ->
                if (request.url.encodedPath.endsWith("/token")) {
                    respond(
                        """{"access_token":"fresh","expires_in":3600,"token_type":"Bearer"}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    attempts++
                    when {
                        request.headers[HttpHeaders.Authorization] == "Bearer valid_token" ->
                            respond("no", status = HttpStatusCode.Unauthorized)
                        attempts < 4 -> respond("down", status = HttpStatusCode.ServiceUnavailable)
                        else -> respond("""{"status":"ok"}""")
                    }
                }
            },
            validTokens,
            retryPolicy = RetryPolicy(maxAttempts = 3),
        )
        assertEquals(HttpStatusCode.OK, c.callAbout().status)
        assertEquals(4, attempts)
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

/**
 * Имитация `java.nio.channels.UnresolvedAddressException`: Ktor CIO на JVM бросает её
 * (подкласс IllegalArgumentException) при недоступном DNS. drive — common-код, поэтому
 * распознаётся по простому имени класса.
 */
private class UnresolvedAddressException : IllegalArgumentException()
