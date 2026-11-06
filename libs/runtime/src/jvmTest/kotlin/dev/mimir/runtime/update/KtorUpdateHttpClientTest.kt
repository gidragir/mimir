package dev.mimir.runtime.update

import dev.mimir.core.Sha256
import dev.mimir.core.toHex
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import dev.mimir.core.update.UpdateHttpStatusException
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class KtorUpdateHttpClientTest {
    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        KtorUpdateHttpClient(HttpClient(MockEngine { handler(it) }), chunkSize = 8 * 1024)

    @Test
    fun streamsInChunksAndHashMatches() = runTest {
        val payload = ByteArray(300_000) { (it * 31).toByte() }
        val c = client { respond(payload, headers = headersOf(HttpHeaders.ContentLength, payload.size.toString())) }
        val hasher = Sha256.Hasher()
        var chunks = 0
        var lastRead = 0L
        var lastTotal = 0L
        val result = c.downloadStream("https://example.com/a.msi") { chunk, read, total ->
            chunks++
            hasher.update(chunk)
            lastRead = read
            lastTotal = total
        }
        assertTrue(result.isSuccess)
        assertEquals(payload.size.toLong(), lastRead)
        assertEquals(payload.size.toLong(), lastTotal)
        assertTrue(chunks > 1, "expected several chunks, got $chunks")
        assertEquals(Sha256.digest(payload).toHex(), hasher.digest().toHex())
    }

    @Test
    fun unknownLengthReportsMinusOne() = runTest {
        val c = client { respond("abc".encodeToByteArray()) }
        val totals = mutableListOf<Long>()
        val result = c.downloadStream("https://example.com/x") { _, _, total -> totals += total }
        assertTrue(result.isSuccess)
        assertTrue(totals.isNotEmpty() && totals.all { it == -1L || it == 3L })
    }

    @Test
    fun non2xxIsFailureWithoutChunks() = runTest {
        val c = client { respondError(HttpStatusCode.NotFound) }
        var chunks = 0
        val result = c.downloadStream("https://example.com/x") { _, _, _ -> chunks++ }
        assertTrue(result.isFailure)
        assertEquals(0, chunks)
    }

    @Test
    fun non2xxIsTypedStatusFailure() = runTest {
        val c = client { respondError(HttpStatusCode.NotFound) }
        val error = c.downloadStream("https://example.com/x") { _, _, _ -> }.exceptionOrNull()
        val status = assertIs<UpdateHttpStatusException>(error)
        assertEquals(404, status.statusCode)
        assertEquals("HTTP 404 for https://example.com/x", status.message)
    }

    @Test
    fun requestsCarryConnectAndSocketTimeoutsButNoWholeRequestTimeout() = runTest {
        var timeouts: HttpTimeoutConfig? = null
        val c = client { req ->
            timeouts = req.getCapabilityOrNull(HttpTimeoutCapability)
            respond("abc".encodeToByteArray())
        }
        c.downloadStream("https://example.com/x") { _, _, _ -> }
        val t = assertNotNull(timeouts, "HttpTimeout must be installed: without it CIO cancels any call after 15 s")
        assertEquals(10_000L, t.connectTimeoutMillis)
        assertEquals(30_000L, t.socketTimeoutMillis)
        assertTrue(t.requestTimeoutMillis == null || t.requestTimeoutMillis == HttpTimeoutConfig.INFINITE_TIMEOUT_MS)
    }

    /**
     * Корень бага на Windows: у движка CIO свой `requestTimeout` (15 с по умолчанию) на весь вызов, включая чтение
     * тела, — MSI дольше 15 с обрывался. Здесь тот же движок с таймаутом 300 мс и тело, которое идёт ~1 с.
     */
    @Test
    fun slowLargeDownloadOutlivesTheCioEngineRequestTimeout() = runBlocking<Unit> {
        val payload = ByteArray(10 * 4096) { it.toByte() }
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val serving = thread(isDaemon = true) {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (input.readLine().orEmpty().isNotEmpty()) Unit
                    val out = socket.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    payload.toList().chunked(4096).forEach { part ->
                        out.write(part.toByteArray())
                        out.flush()
                        Thread.sleep(100)
                    }
                }
            }
            val engine = HttpClient(CIO) { engine { requestTimeout = 300 } }
            val received = java.io.ByteArrayOutputStream()
            val result = engine.use {
                KtorUpdateHttpClient(it).downloadStream("http://127.0.0.1:${server.localPort}/a.msi") { chunk, _, _ -> received.write(chunk) }
            }
            serving.join(5_000)
            assertTrue(result.isSuccess, "download failed: ${result.exceptionOrNull()}")
            assertTrue(received.toByteArray().contentEquals(payload))
        }
    }

    @Test
    fun midStreamFailureIsFailure() = runTest {
        val c = client {
            val ch = ByteChannel()
            launch {
                ch.writeFully(ByteArray(1000))
                ch.flush()
                ch.close(java.io.IOException("connection reset"))
            }
            respond(ch, headers = headersOf(HttpHeaders.ContentLength, "5000"))
        }
        var received = 0L
        val result = c.downloadStream("https://example.com/x") { _, read, _ -> received = read }
        assertTrue(result.isFailure)
        assertTrue(received <= 1000)
    }

    @Test
    fun getReturnsStatusHeadersAndBody() = runTest {
        var sent: Map<String, String> = emptyMap()
        val c = client { req ->
            sent = req.headers.entries().associate { it.key to it.value.first() }
            respond(
                "{}",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ETag to listOf("\"abc\""), "X-Multi" to listOf("a", "b")),
            )
        }
        val r = c.get("https://api.github.com/x", mapOf("If-None-Match" to "\"old\""))
        assertEquals(200, r.statusCode)
        assertEquals("{}", r.bodyText)
        assertEquals("\"abc\"", r.headers.entries.first { it.key.equals("etag", true) }.value)
        assertEquals("a,b", r.headers.entries.first { it.key.equals("x-multi", true) }.value)
        assertEquals("\"old\"", sent["If-None-Match"])
    }

    @Test
    fun getDoesNotThrowOn304And429() = runTest {
        val notModified = client { respond("", HttpStatusCode.NotModified) }
        assertEquals(304, notModified.get("https://x").statusCode)
        val limited = client { respond("slow", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "120")) }
        val r = limited.get("https://x")
        assertEquals(429, r.statusCode)
        assertEquals("120", r.headers.entries.first { it.key.equals("retry-after", true) }.value)
    }
}
