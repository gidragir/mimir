package dev.mimir.drive.upload

import dev.mimir.core.RemotePath
import dev.mimir.drive.auth.GoogleAuthClient
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
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

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
        val authClient = GoogleAuthClient(http, "client-id")
        val storage = InMemoryTokenStorage(OAuthTokens("access", "refresh", 2000000000L))
        val driveClient = DriveHttpClient(http, storage, authClient)
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
                    if (contentRange == "bytes */5242880") {
                        // Запрос смещения после сбоя
                        respond("", HttpStatusCode(308, "Resume Incomplete"), headersOf("Range", "bytes=0-1048575"))
                    } else if (contentRange.startsWith("bytes 0-1048575/")) {
                        chunkAttempts++
                        respond("", HttpStatusCode(308, "Resume Incomplete"), headersOf("Range", "bytes=0-1048575"))
                    } else if (contentRange.startsWith("bytes 1048576-")) {
                        // Успешное завершение передачи
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
        val authClient = GoogleAuthClient(http, "client-id")
        val storage = InMemoryTokenStorage(OAuthTokens("access", "refresh", 2000000000L))
        val driveClient = DriveHttpClient(http, storage, authClient)
        val resolver = DrivePathResolver(driveClient, "Mimir")
        val uploader = DriveUploader(driveClient, resolver, resumableThresholdBytes = 1024, chunkSize = 1048576)

        val totalSize = 5242880L
        val data = ByteArray(totalSize.toInt()) { (it % 128).toByte() }
        val buffer = Buffer().also { it.write(data) }

        val entry = uploader.upload(RemotePath("large.bin"), buffer)
        assertEquals("resumed_hash", entry.revision)
        assertEquals(totalSize, entry.size)
    }
}
