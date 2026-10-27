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
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** JS/wasm читают тело целиком (readBuffer), потоковость проверяется на JVM/Android. */
class GoogleDriveStorageLazyBodyTest {
    @Test
    fun readReturnsBeforeWholeBodyHasArrived() = runTest {
        val body = ByteChannel()
        body.writeFully(ByteArray(1024) { 1 })
        body.flush()
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        val mockEngine = MockEngine { request ->
            when {
                request.url.parameters["q"]?.contains("'root' in parents") == true ->
                    respond("""{"files":[{"id":"mimir_root","name":"Mimir","mimeType":"application/vnd.google-apps.folder"}]}""", HttpStatusCode.OK, jsonHeaders)
                request.url.parameters["q"]?.contains("'mimir_root' in parents") == true ->
                    respond("""{"files":[{"id":"big","name":"big.bin","mimeType":"application/octet-stream","size":"999999999","trashed":false}]}""", HttpStatusCode.OK, jsonHeaders)
                request.url.parameters["alt"] == "media" -> respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/octet-stream"))
                else -> respond("{}", HttpStatusCode.OK, jsonHeaders)
            }
        }
        val http = HttpClient(mockEngine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val authClient = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "client-id", null))
        val driveClient = DriveHttpClient(http, InMemoryTokenStorage(OAuthTokens("access", "refresh", 2000000000L)), GoogleAuthClients(null, authClient))
        val driveStorage = GoogleDriveStorage(driveClient, DrivePathResolver(driveClient, "Mimir"))

        val source = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5_000) { driveStorage.read(RemotePath("big.bin")) }
        }
        body.close()
        assertNotNull(source, "read() must not wait for the whole body to be buffered")
    }

    @Test
    fun hundredMegabyteDownloadKeepsOnlyABoundedWindowInFlight() = runTest {
        val total = 100L * 1024 * 1024
        val chunk = ByteArray(64 * 1024)
        val written = java.util.concurrent.atomic.AtomicLong()
        val consumed = java.util.concurrent.atomic.AtomicLong()
        val maxInFlight = java.util.concurrent.atomic.AtomicLong()
        val body = ByteChannel()
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        val mockEngine = MockEngine { request ->
            when {
                request.url.parameters["q"]?.contains("'root' in parents") == true ->
                    respond("""{"files":[{"id":"mimir_root","name":"Mimir","mimeType":"application/vnd.google-apps.folder"}]}""", HttpStatusCode.OK, jsonHeaders)
                request.url.parameters["q"]?.contains("'mimir_root' in parents") == true ->
                    respond("""{"files":[{"id":"big","name":"big.bin","mimeType":"application/octet-stream","size":"$total","trashed":false}]}""", HttpStatusCode.OK, jsonHeaders)
                request.url.parameters["alt"] == "media" -> respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/octet-stream"))
                else -> respond("{}", HttpStatusCode.OK, jsonHeaders)
            }
        }
        val http = HttpClient(mockEngine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val authClient = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "client-id", null))
        val driveClient = DriveHttpClient(http, InMemoryTokenStorage(OAuthTokens("access", "refresh", 2000000000L)), GoogleAuthClients(null, authClient))
        val driveStorage = GoogleDriveStorage(driveClient, DrivePathResolver(driveClient, "Mimir"))

        withContext(Dispatchers.Default) {
            val producer = launch {
                while (written.get() < total) {
                    body.writeFully(chunk)
                    maxInFlight.accumulateAndGet(written.addAndGet(chunk.size.toLong()) - consumed.get()) { a, b -> maxOf(a, b) }
                }
                body.close()
            }
            val source = driveStorage.read(RemotePath("big.bin"))
            val sink = kotlinx.io.Buffer()
            while (true) {
                sink.clear()
                val n = source.readAtMostTo(sink, 64L * 1024)
                if (n < 0) break
                consumed.addAndGet(n)
            }
            producer.join()
        }
        assertEquals(total, consumed.get())
        assertTrue(maxInFlight.get() < 8L * 1024 * 1024, "in flight ${maxInFlight.get()} bytes")
    }
}
