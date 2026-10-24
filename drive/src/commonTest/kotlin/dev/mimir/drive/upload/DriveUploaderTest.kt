package dev.mimir.drive.upload

import dev.mimir.core.RemotePath
import dev.mimir.drive.auth.GoogleAuthClient
import dev.mimir.drive.auth.GoogleAuthClients
import dev.mimir.drive.auth.GoogleClientCredentials
import dev.mimir.drive.auth.GoogleClientKind
import dev.mimir.drive.auth.InMemoryTokenStorage
import dev.mimir.drive.auth.OAuthTokens
import dev.mimir.drive.client.DriveHttpClient
import dev.mimir.drive.path.DrivePathResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DriveUploaderTest {
    @Test
    fun testSimpleUploadSmallFile() = runTest {
        val mockEngine = MockEngine { request ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                request.url.parameters["q"]?.contains("'root' in parents") == true -> {
                    respond("""{"files":[{"id":"mimir_root","name":"Mimir","mimeType":"application/vnd.google-apps.folder"}]}""", HttpStatusCode.OK, jsonHeaders)
                }
                request.url.encodedPath == "/upload/drive/v3/files" -> {
                    respond("""
                        {
                            "id": "uploaded_small_id",
                            "name": "small.txt",
                            "mimeType": "text/plain",
                            "size": "5",
                            "modifiedTime": "2026-10-23T12:00:00.000Z",
                            "md5Checksum": "md5hash123"
                        }
                    """.trimIndent(), HttpStatusCode.OK, jsonHeaders)
                }
                else -> respond("""{"files":[]}""", HttpStatusCode.OK, jsonHeaders)
            }
        }

        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val authClient = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "client-id", null))
        val storage = InMemoryTokenStorage(OAuthTokens("access", "refresh", 2000000000L))
        val driveClient = DriveHttpClient(http, storage, GoogleAuthClients(null, authClient))
        val resolver = DrivePathResolver(driveClient, "Mimir")
        val uploader = DriveUploader(driveClient, resolver)

        val buffer = Buffer().also { it.write("Hello".encodeToByteArray()) }
        val entry = uploader.upload(RemotePath("small.txt"), buffer)
        assertEquals(RemotePath("small.txt"), entry.path)
        assertEquals(5L, entry.size)
        assertEquals("md5hash123", entry.revision)
    }

    @Test
    fun testResumableUploadWithNetworkInterruptionAndResume() = runTest {
        var chunkAttempts = 0
        val mockEngine = MockEngine { request ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                request.url.parameters["q"]?.contains("'root' in parents") == true -> {
                    respond("""{"files":[{"id":"mimir_root","name":"Mimir","mimeType":"application/vnd.google-apps.folder"}]}""", HttpStatusCode.OK, jsonHeaders)
                }
                request.url.parameters["uploadType"] == "resumable" -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.Location, "https://upload.session.url/12345")
                    )
                }
                request.url.toString() == "https://upload.session.url/12345" -> {
                    val contentRange = request.headers["Content-Range"] ?: ""
                    val match = Regex("""bytes (\d+)-(\d+)/(.+)""").matchEntire(contentRange)
                    if (match == null) {
                        respond("Error", HttpStatusCode.BadRequest)
                    } else if (match.groupValues[3] == "*") {
                        chunkAttempts++
                        respond("", HttpStatusCode(308, "Resume Incomplete"), headersOf("Range", "bytes=0-${match.groupValues[2]}"))
                    } else {
                        respond("""
                            {
                                "id": "resumed_file_id",
                                "name": "large.bin",
                                "mimeType": "application/octet-stream",
                                "size": "5242880",
                                "modifiedTime": "2026-10-23T12:00:00.000Z",
                                "md5Checksum": "resumed_hash"
                            }
                        """.trimIndent(), HttpStatusCode.OK, jsonHeaders)
                    }
                }
                else -> respond("""{"files":[]}""", HttpStatusCode.OK, jsonHeaders)
            }
        }

        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val authClient = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "client-id", null))
        val storage = InMemoryTokenStorage(OAuthTokens("access", "refresh", 2000000000L))
        val driveClient = DriveHttpClient(http, storage, GoogleAuthClients(null, authClient))
        val resolver = DrivePathResolver(driveClient, "Mimir")
        val uploader = DriveUploader(driveClient, resolver, resumableThresholdBytes = 1024, chunkSize = 1048576)

        val totalSize = 5242880L
        val data = ByteArray(totalSize.toInt()) { (it % 128).toByte() }
        val buffer = Buffer().also { it.write(data) }

        val entry = uploader.upload(RemotePath("large.bin"), buffer)
        assertEquals("resumed_hash", entry.revision)
        assertEquals(totalSize, entry.size)
        assertEquals(4, chunkAttempts) // 5 МиБ блоками по 1 МиБ: четыре с "/*" и последний с итоговым размером
    }

    @Test
    fun testResumableUploadRecoversFromIOExceptionDuringChunkUpload() = runTest {
        var failedOnce = false
        val mockEngine = MockEngine { request ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                request.url.parameters["q"]?.contains("'root' in parents") == true -> {
                    respond("""{"files":[{"id":"mimir_root","name":"Mimir","mimeType":"application/vnd.google-apps.folder"}]}""", HttpStatusCode.OK, jsonHeaders)
                }
                request.url.parameters["uploadType"] == "resumable" -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.Location, "https://upload.session.url/recoverable")
                    )
                }
                request.url.toString() == "https://upload.session.url/recoverable" -> {
                    val contentRange = request.headers["Content-Range"] ?: ""
                    if (contentRange == "bytes */*") {
                        // Опрос смещения после исключения сети
                        respond("", HttpStatusCode(308, "Resume Incomplete"), headersOf("Range", "bytes=0-1023"))
                    } else if (contentRange.startsWith("bytes 0-1023/")) {
                        if (!failedOnce) {
                            failedOnce = true
                            throw io.ktor.client.network.sockets.SocketTimeoutException("Network timeout")
                        }
                        respond("", HttpStatusCode(308, "Resume Incomplete"), headersOf("Range", "bytes=0-1023"))
                    } else if (contentRange.startsWith("bytes 1024-")) {
                        respond("""
                            {
                                "id": "recovered_file_id",
                                "name": "recovered.bin",
                                "mimeType": "application/octet-stream",
                                "size": "2048",
                                "modifiedTime": "2026-10-23T12:00:00.000Z",
                                "md5Checksum": "rec_hash"
                            }
                        """.trimIndent(), HttpStatusCode.OK, jsonHeaders)
                    } else {
                        respond("Error", HttpStatusCode.BadRequest)
                    }
                }
                else -> respond("""{"files":[]}""", HttpStatusCode.OK, jsonHeaders)
            }
        }

        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val authClient = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "client-id", null))
        val storage = InMemoryTokenStorage(OAuthTokens("access", "refresh", 2000000000L))
        val driveClient = DriveHttpClient(http, storage, GoogleAuthClients(null, authClient))
        val resolver = DrivePathResolver(driveClient, "Mimir")
        val uploader = DriveUploader(driveClient, resolver, resumableThresholdBytes = 1024, chunkSize = 1024)

        val totalSize = 2048L
        val data = ByteArray(totalSize.toInt()) { (it % 128).toByte() }
        val buffer = Buffer().also { it.write(data) }

        val entry = uploader.upload(RemotePath("recovered.bin"), buffer)
        assertEquals("rec_hash", entry.revision)
        assertEquals(totalSize, entry.size)
    }

    /** Запись PUT-запроса к resumable-сессии: заголовок Content-Range и копия тела. */
    private class SessionPut(val contentRange: String, val body: ByteArray)

    private class Harness(
        val uploader: DriveUploader,
        val multipartRequests: List<String>,
        val resumableInits: List<io.ktor.client.request.HttpRequestData>,
        val puts: List<SessionPut>,
    )

    /**
     * Drive-заглушка: multipart отдаёт файл `size = null`, resumable-сессия через [session] получает каждый PUT
     * с телом (копия: буфер блока переиспользуется).
     */
    private fun harness(
        threshold: Long,
        chunk: Int,
        session: MockRequestHandleScope.(SessionPut) -> HttpResponseData,
    ): Harness {
        val multipart = mutableListOf<String>()
        val inits = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val puts = mutableListOf<SessionPut>()
        val engine = MockEngine { request ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                request.url.parameters["q"]?.contains("'root' in parents") == true ->
                    respond("""{"files":[{"id":"mimir_root","name":"Mimir","mimeType":"application/vnd.google-apps.folder"}]}""", HttpStatusCode.OK, jsonHeaders)
                request.url.parameters["uploadType"] == "multipart" -> {
                    multipart += request.url.toString()
                    respond("""{"id":"multi_id","name":"f","mimeType":"application/octet-stream","md5Checksum":"multi_md5"}""", HttpStatusCode.OK, jsonHeaders)
                }
                request.url.parameters["uploadType"] == "resumable" -> {
                    inits += request
                    respond("", HttpStatusCode.OK, headersOf(HttpHeaders.Location, "https://upload.session.url/s"))
                }
                request.url.toString() == "https://upload.session.url/s" -> {
                    val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.copyOf() ?: ByteArray(0)
                    val put = SessionPut(request.headers["Content-Range"] ?: "", body)
                    puts += put
                    session(put)
                }
                else -> respond("""{"files":[]}""", HttpStatusCode.OK, jsonHeaders)
            }
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val authClient = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "client-id", null))
        val storage = InMemoryTokenStorage(OAuthTokens("access", "refresh", 2000000000L))
        val driveClient = DriveHttpClient(http, storage, GoogleAuthClients(null, authClient))
        val uploader = DriveUploader(driveClient, DrivePathResolver(driveClient, "Mimir"), threshold, chunk)
        return Harness(uploader, multipart, inits, puts)
    }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val incomplete = HttpStatusCode(308, "Resume Incomplete")
    private val doneJson = """{"id":"done_id","name":"f","mimeType":"application/octet-stream","md5Checksum":"done_md5"}"""

    /** 308 с `Range` до конца присланного блока для блоков с неизвестным размером, 200 с файлом для итогового размера. */
    private fun MockRequestHandleScope.ackOrFinish(put: SessionPut): HttpResponseData {
        val m = Regex("""bytes (\d+)-(\d+)/(.+)""").matchEntire(put.contentRange)
            ?: return respond("bad range ${put.contentRange}", HttpStatusCode.BadRequest)
        return if (m.groupValues[3] == "*") {
            respond("", incomplete, headersOf("Range", "bytes=0-${m.groupValues[2]}"))
        } else {
            respond(doneJson, HttpStatusCode.OK, jsonHeaders)
        }
    }

    private fun sourceBytes(size: Int) = ByteArray(size) { ((it * 31) xor (it ushr 8)).toByte() }

    /** Источник, считающий прочитанные байты (проверка, что файл не читается целиком наперёд). */
    private class CountingSource(bytes: ByteArray) : RawSource {
        private val inner = Buffer().also { it.write(bytes) }
        var bytesRead = 0L
            private set

        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            val n = inner.readAtMostTo(sink, byteCount)
            if (n > 0) bytesRead += n
            return n
        }

        override fun close() = inner.close()
    }

    @Test
    fun largeUploadStreamsChunksWithUnknownTotal() = runTest {
        val threshold = 1L * 1024 * 1024
        val chunk = 256 * 1024
        val total = (threshold + 2_500_000L / 1024 * 1024 + 512 * 1024).toInt() // 1 МиБ + ~2.5 МиБ, последний блок неполный
        val data = sourceBytes(total)
        val source = CountingSource(data)
        var maxReadAhead = 0L
        var sentEnd = 0L
        val h = harness(threshold, chunk) { put ->
            sentEnd = Regex("""bytes \d+-(\d+)/""").find(put.contentRange)!!.groupValues[1].toLong() + 1
            maxReadAhead = maxOf(maxReadAhead, source.bytesRead - sentEnd)
            ackOrFinish(put)
        }

        val entry = h.uploader.upload(RemotePath("large.bin"), source.buffered())

        assertEquals(total.toLong(), entry.size)
        assertEquals("done_md5", entry.revision)
        assertEquals(0, h.multipartRequests.size)
        assertNull(h.resumableInits.single().headers["X-Upload-Content-Length"])
        val ranges = h.puts.map { it.contentRange }
        assertTrue(ranges.dropLast(1).all { it.endsWith("/*") }, ranges.toString())
        assertEquals("/$total", "/" + ranges.last().substringAfter('/'))
        assertTrue(h.puts.dropLast(1).all { it.body.size == chunk }, "non-last chunks must be full")
        assertContentEquals(data, h.puts.fold(ByteArray(0)) { acc, p -> acc + p.body })
        // До первого PUT прочитан не больше, чем порог + блок + буфер Source; весь файл целиком не читается.
        assertTrue(maxReadAhead <= threshold + 64 * 1024, "read ahead $maxReadAhead")
    }

    @Test
    fun exactMultipleOfChunkHasNoEmptyTrailingChunk() = runTest {
        val chunk = 256 * 1024
        val total = 8 * chunk // 2 МиБ, порог 1 МиБ
        val data = sourceBytes(total)
        val h = harness(1024L * 1024, chunk) { put -> ackOrFinish(put) }

        val entry = h.uploader.upload(RemotePath("x.bin"), Buffer().also { it.write(data) })

        assertEquals(8, h.puts.size)
        assertEquals("bytes ${7 * chunk}-${total - 1}/$total", h.puts.last().contentRange)
        assertTrue(h.puts.dropLast(1).all { it.contentRange.endsWith("/*") })
        assertTrue(h.puts.none { it.body.isEmpty() })
        assertContentEquals(data, h.puts.fold(ByteArray(0)) { acc, p -> acc + p.body })
        assertEquals(total.toLong(), entry.size) // Drive-ответ без size → отправлено байт
    }

    @Test
    fun emptyFileUsesSingleMultipart() = runTest {
        val h = harness(1024L * 1024, 256 * 1024) { _ -> error("no resumable PUT expected") }

        val entry = h.uploader.upload(RemotePath("empty.md"), Buffer())

        assertEquals(1, h.multipartRequests.size)
        assertEquals(0, h.resumableInits.size)
        assertEquals(0, h.puts.size)
        assertEquals(0L, entry.size)
        assertEquals("multi_md5", entry.revision)
    }

    /** Граница: размер == порогу → multipart (источник исчерпан после чтения порога). */
    @Test
    fun fileExactlyAtThresholdUsesMultipart() = runTest {
        val threshold = 1024L
        val h = harness(threshold, 256) { _ -> error("no resumable PUT expected") }

        h.uploader.upload(RemotePath("at.bin"), Buffer().also { it.write(sourceBytes(threshold.toInt())) })

        assertEquals(1, h.multipartRequests.size)
        assertEquals(0, h.resumableInits.size)
    }

    /** Граница: порог + 1 байт → resumable, единственный блок уже последний и несёт итог. */
    @Test
    fun fileOneByteOverThresholdUsesResumableWithTotal() = runTest {
        val threshold = 1024L
        val h = harness(threshold, 4096) { put -> ackOrFinish(put) }

        h.uploader.upload(RemotePath("over.bin"), Buffer().also { it.write(sourceBytes(1025)) })

        assertEquals(0, h.multipartRequests.size)
        assertEquals(listOf("bytes 0-1024/1025"), h.puts.map { it.contentRange })
    }

    @Test
    fun partialAckResendsTailOfCurrentChunk() = runTest {
        val data = sourceBytes(2500)
        var first = true
        val h = harness(1024, 1024) { put ->
            if (first && put.contentRange == "bytes 0-1023/*") {
                first = false
                respond("", incomplete, headersOf("Range", "bytes=0-100"))
            } else {
                ackOrFinish(put)
            }
        }

        h.uploader.upload(RemotePath("p.bin"), Buffer().also { it.write(data) })

        assertEquals(
            listOf("bytes 0-1023/*", "bytes 101-1023/*", "bytes 1024-2047/*", "bytes 2048-2499/2500"),
            h.puts.map { it.contentRange },
        )
        assertContentEquals(data.copyOfRange(101, 1024), h.puts[1].body)
        assertContentEquals(data.copyOfRange(1024, 2048), h.puts[2].body)
    }

    @Test
    fun failureOnLastChunkQueriesStatusWithKnownTotal() = runTest {
        val data = sourceBytes(2500)
        var failedOnce = false
        val h = harness(1024, 1024) { put ->
            when {
                put.contentRange == "bytes */2500" -> respond("", incomplete, headersOf("Range", "bytes=0-2047"))
                put.contentRange == "bytes 2048-2499/2500" && !failedOnce -> {
                    failedOnce = true
                    throw io.ktor.client.network.sockets.SocketTimeoutException("timeout")
                }
                else -> ackOrFinish(put)
            }
        }

        val entry = h.uploader.upload(RemotePath("r.bin"), Buffer().also { it.write(data) })

        assertEquals("done_md5", entry.revision)
        assertEquals(
            listOf("bytes 0-1023/*", "bytes 1024-2047/*", "bytes 2048-2499/2500", "bytes */2500", "bytes 2048-2499/2500"),
            h.puts.map { it.contentRange },
        )
    }

    @Test
    fun statusQueryReportingCompletionReturnsTheFile() = runTest {
        val h = harness(1024, 1024) { put ->
            when {
                put.contentRange == "bytes */1500" -> respond(doneJson, HttpStatusCode.OK, jsonHeaders)
                put.contentRange == "bytes 1024-1499/1500" -> throw io.ktor.client.network.sockets.SocketTimeoutException("timeout")
                else -> ackOrFinish(put)
            }
        }

        val entry = h.uploader.upload(RemotePath("c.bin"), Buffer().also { it.write(sourceBytes(1500)) })

        assertEquals("done_md5", entry.revision)
    }

    @Test
    fun lostBytesBeforeCurrentChunkFailInsteadOfCorruptingTheFile() = runTest {
        val h = harness(1024, 1024) { put ->
            if (put.contentRange == "bytes 1024-2047/*") {
                respond("", incomplete, headersOf("Range", "bytes=0-10")) // Drive «забыл» уже отброшенные байты
            } else {
                ackOrFinish(put)
            }
        }

        assertFailsWith<IllegalStateException> {
            h.uploader.upload(RemotePath("l.bin"), Buffer().also { it.write(sourceBytes(3000)) })
        }
    }

    @Test
    fun noProgressAckThrowsInsteadOfLooping() = runTest {
        val h = harness(1024, 1024) { put ->
            // Всегда подтверждаем только первые 101 байт: после первого продвижения дальше ни шагу.
            if (put.contentRange.endsWith("/*")) respond("", incomplete, headersOf("Range", "bytes=0-100")) else ackOrFinish(put)
        }

        assertFailsWith<IllegalStateException> {
            h.uploader.upload(RemotePath("s.bin"), Buffer().also { it.write(sourceBytes(3000)) })
        }
        assertTrue(h.puts.size <= 10, "bounded requests, got ${h.puts.size}")
    }

    @Test
    fun ackBeyondSentBytesThrows() = runTest {
        val h = harness(1024, 1024) { put ->
            if (put.contentRange.endsWith("/*")) respond("", incomplete, headersOf("Range", "bytes=0-5000")) else ackOrFinish(put)
        }

        assertFailsWith<IllegalStateException> {
            h.uploader.upload(RemotePath("a.bin"), Buffer().also { it.write(sourceBytes(3000)) })
        }
        assertEquals(1, h.puts.size)
    }
}
