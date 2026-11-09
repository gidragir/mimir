package dev.mimir.drive

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
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoogleDriveStorageRangedReadTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private class Fixture(val storage: GoogleDriveStorage, val mediaRequests: MutableList<HttpRequestData>)

    private fun fixture(
        data: ByteArray,
        chunk: Long?,
        honorRange: Boolean = true,
        mediaOverride: (MockRequestHandleScope.(HttpRequestData, Int) -> HttpResponseData?)? = null,
    ): Fixture {
        val media = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            val q = request.url.parameters["q"]
            when {
                request.url.encodedPath.endsWith("/token") ->
                    respond("""{"access_token":"fresh","expires_in":3600,"token_type":"Bearer"}""", HttpStatusCode.OK, jsonHeaders)
                q?.contains("'root' in parents") == true ->
                    respond("""{"files":[{"id":"mimir_root","name":"Mimir","mimeType":"application/vnd.google-apps.folder"}]}""", HttpStatusCode.OK, jsonHeaders)
                q?.contains("'mimir_root' in parents") == true ->
                    respond(
                        """{"files":[{"id":"f1","name":"big.bin","mimeType":"application/octet-stream","size":"${data.size}","modifiedTime":"2026-10-23T12:00:00.000Z","md5Checksum":"h","trashed":false}]}""",
                        HttpStatusCode.OK, jsonHeaders,
                    )
                request.url.encodedPath == "/drive/v3/files/f1" && request.url.parameters["alt"] == "media" -> {
                    media += request
                    mediaOverride?.invoke(this, request, media.size)
                        ?: rangeResponse(request, data, honorRange)
                }
                else -> respond("{}", HttpStatusCode.OK, jsonHeaders)
            }
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val auth = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "id", null))
        val tokens = InMemoryTokenStorage(OAuthTokens("access", "refresh", 2000000000L))
        val drive = DriveHttpClient(http, tokens, GoogleAuthClients(null, auth))
        return Fixture(GoogleDriveStorage(drive, DrivePathResolver(drive, "Mimir"), downloadChunkBytes = chunk), media)
    }

    private fun MockRequestHandleScope.rangeResponse(request: HttpRequestData, data: ByteArray, honorRange: Boolean): HttpResponseData {
        val range = request.headers[HttpHeaders.Range]
        if (range == null || !honorRange) return respond(data, HttpStatusCode.OK)
        val (a, b) = range.removePrefix("bytes=").split("-").map { it.toLong() }
        if (a >= data.size) {
            return respond(ByteArray(0), HttpStatusCode.RequestedRangeNotSatisfiable, headersOf(HttpHeaders.ContentRange, "bytes */${data.size}"))
        }
        val end = minOf(b, data.size - 1L)
        return respond(
            data.copyOfRange(a.toInt(), end.toInt() + 1),
            HttpStatusCode.PartialContent,
            headersOf(HttpHeaders.ContentRange, "bytes $a-$end/${data.size}"),
        )
    }

    private fun bytes(n: Int) = ByteArray(n) { (it * 7 + 3).toByte() }

    @Test
    fun rangedReadConcatenatesChunksInOrder() = runTest {
        val data = bytes(20)
        val f = fixture(data, 8)
        assertContentEquals(data, f.storage.read(RemotePath("big.bin")).readByteArray())
        assertEquals(listOf("bytes=0-7", "bytes=8-15", "bytes=16-23"), f.mediaRequests.map { it.headers[HttpHeaders.Range] })
    }

    @Test
    fun rangedReadStopsAtTotalFromContentRange() = runTest {
        val data = bytes(16)
        val f = fixture(data, 8)
        assertContentEquals(data, f.storage.read(RemotePath("big.bin")).readByteArray())
        assertEquals(2, f.mediaRequests.size)
    }

    @Test
    fun rangedReadEmptyFile() = runTest {
        val f = fixture(ByteArray(0), 8)
        assertEquals(0, f.storage.read(RemotePath("big.bin")).readByteArray().size)
        assertEquals(1, f.mediaRequests.size)
    }

    @Test
    fun rangedRead200IgnoresRange() = runTest {
        val data = bytes(20)
        val f = fixture(data, 8, honorRange = false)
        assertContentEquals(data, f.storage.read(RemotePath("big.bin")).readByteArray())
        assertEquals(1, f.mediaRequests.size)
    }

    @Test
    fun rangedReadRefreshesTokenMidFile() = runTest {
        val data = bytes(16)
        var failed = false
        val f = fixture(data, 8, mediaOverride = { request, n ->
            if (n == 2 && !failed) {
                failed = true
                respond("", HttpStatusCode.Unauthorized)
            } else {
                rangeResponse(request, data, true)
            }
        })
        assertContentEquals(data, f.storage.read(RemotePath("big.bin")).readByteArray())
        val ranges = f.mediaRequests.map { it.headers[HttpHeaders.Range] }
        assertEquals(listOf("bytes=0-7", "bytes=8-15", "bytes=8-15"), ranges)
        assertEquals("Bearer fresh", f.mediaRequests.last().headers[HttpHeaders.Authorization])
    }

    @Test
    fun rangedReadCancellationClosesChannel() = runTest {
        val data = bytes(64 * 1024)
        val f = fixture(data, 8 * 1024)
        val source = f.storage.read(RemotePath("big.bin"))
        source.readByteArray(1) // потребитель взял начало и ушёл
        source.close()
        withContext(Dispatchers.Default) { delay(100) }
        val seen = f.mediaRequests.size
        withContext(Dispatchers.Default) { delay(200) }
        assertEquals(seen, f.mediaRequests.size, "no new requests after the consumer is gone")
        assertTrue(seen <= 8)
    }

    @Test
    fun nullChunkKeepsSingleStreamingGet() = runTest {
        val data = bytes(20)
        val f = fixture(data, null)
        assertContentEquals(data, f.storage.read(RemotePath("big.bin")).readByteArray())
        assertEquals(1, f.mediaRequests.size)
        assertEquals(null, f.mediaRequests[0].headers[HttpHeaders.Range])
    }
}
