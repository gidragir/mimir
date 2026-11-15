package dev.mimir.drive.client

import dev.mimir.core.RemoteUnavailableException
import dev.mimir.core.RequestSample
import dev.mimir.core.SyncMetrics
import dev.mimir.drive.auth.GoogleAuthClient
import dev.mimir.drive.auth.GoogleAuthClients
import dev.mimir.drive.auth.GoogleClientCredentials
import dev.mimir.drive.auth.GoogleClientKind
import dev.mimir.drive.auth.InMemoryTokenStorage
import dev.mimir.drive.auth.OAuthTokens
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DriveHttpClientMetricsTest {
    @Test
    fun everyAttemptProducesOneSampleWithOpAndReason() = runTest {
        val recorded = mutableListOf<RequestSample>()
        val metrics = object : SyncMetrics {
            override suspend fun onRequest(sample: RequestSample) {
                recorded += sample
            }
        }
        val client = clientWith(
            responses = listOf(
                { rateLimit403("userRateLimitExceeded") },
                { tooMany(retryAfter = "1") },
                { ok() },
            ),
            metrics = metrics,
        )
        client.executeWithRetry(DriveOp.ListQuery) {
            client.httpClient.get("https://www.googleapis.com/drive/v3/files")
        }
        assertEquals(listOf(403, 429, 200), recorded.map { it.status })
        assertEquals(listOf("userRateLimitExceeded", null, null), recorded.map { it.reason })
        assertEquals(listOf(1, 2, 3), recorded.map { it.attempt })
        assertTrue(recorded.all { it.op == "list-q" && !it.isWrite })
        assertEquals(1000L, recorded[2].waitedBeforeMs)
    }

    @Test
    fun networkFailureIsRecordedBeforeRemoteUnavailable() = runTest {
        val recorded = mutableListOf<RequestSample>()
        val metrics = object : SyncMetrics {
            override suspend fun onRequest(sample: RequestSample) {
                recorded += sample
            }
        }
        val client = clientWith(
            responses = List(4) { { throw IOException("network down") } },
            metrics = metrics,
        )
        assertFailsWith<RemoteUnavailableException> {
            client.executeWithRetry(DriveOp.GetMeta) {
                client.httpClient.get("https://www.googleapis.com/drive/v3/files/abc")
            }
        }
        assertTrue(recorded.isNotEmpty())
        assertTrue(recorded.all { it.networkError && it.op == "get-meta" && !it.isWrite })
    }

    @Test
    fun streamingFailureInConsumeDoesNotEmitDuplicateSample() = runTest {
        val recorded = mutableListOf<RequestSample>()
        val metrics = object : SyncMetrics {
            override suspend fun onRequest(sample: RequestSample) {
                recorded += sample
            }
        }
        val client = clientWith(
            responses = listOf { ok() },
            metrics = metrics,
        )
        assertFailsWith<IllegalStateException> {
            client.executeStreaming(
                op = DriveOp.GetMedia,
                request = { client.httpClient.prepareGet("https://www.googleapis.com/drive/v3/files/abc?alt=media") },
                consume = { throw IllegalStateException("boom") },
            )
        }
        assertEquals(1, recorded.size)
        assertEquals(200, recorded[0].status)
        assertEquals("get-media", recorded[0].op)
    }

    private fun clientWith(
        responses: List<suspend MockRequestHandleScope.() -> HttpResponseData>,
        metrics: SyncMetrics,
    ): DriveHttpClient {
        var idx = 0
        val engine = MockEngine {
            val handler = responses.getOrElse(idx++) { responses.last() }
            handler()
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val authClient = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "client-id", null))
        val storage = InMemoryTokenStorage(OAuthTokens("valid_token", "refresh", 2_000_000_000L))
        return DriveHttpClient(
            httpClient = http,
            tokenStorage = storage,
            authClients = GoogleAuthClients(null, authClient),
            retryPolicy = RetryPolicy(maxAttempts = 3, baseDelay = 1.milliseconds, maxDelay = 5.seconds),
            random = Random(0),
            metrics = metrics,
        )
    }

    private fun MockRequestHandleScope.rateLimit403(reason: String): HttpResponseData = respond(
        content = """{"error":{"code":403,"errors":[{"reason":"$reason"}]}}""",
        status = HttpStatusCode.Forbidden,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private fun MockRequestHandleScope.tooMany(retryAfter: String): HttpResponseData = respond(
        content = "Too Many Requests",
        status = HttpStatusCode.TooManyRequests,
        headers = headersOf(HttpHeaders.RetryAfter, retryAfter),
    )

    private fun MockRequestHandleScope.ok(): HttpResponseData = respond(
        content = """{"files":[]}""",
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
}
