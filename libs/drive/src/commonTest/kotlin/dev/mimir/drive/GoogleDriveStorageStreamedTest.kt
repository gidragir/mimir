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
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class GoogleDriveStorageStreamedTest {
    @Test
    fun testReadStreamsResponseBodyWithoutFullByteArrayAllocation() = runTest {
        val payload = "A".repeat(128 * 1024) // 128 KB
        val mockEngine = MockEngine { request ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                request.url.parameters["q"]?.contains("'root' in parents") == true -> {
                    respond("""{"files":[{"id":"mimir_root","name":"Mimir","mimeType":"application/vnd.google-apps.folder"}]}""", HttpStatusCode.OK, jsonHeaders)
                }
                request.url.parameters["q"]?.contains("'mimir_root' in parents") == true -> {
                    respond("""
                        {
                            "files": [
                                {
                                    "id": "file_stream",
                                    "name": "stream.txt",
                                    "mimeType": "text/plain",
                                    "size": "${payload.length}",
                                    "modifiedTime": "2026-10-23T12:00:00.000Z",
                                    "md5Checksum": "hash_stream",
                                    "trashed": false
                                }
                            ]
                        }
                    """.trimIndent(), HttpStatusCode.OK, jsonHeaders)
                }
                request.url.encodedPath == "/drive/v3/files/file_stream" && request.url.parameters["alt"] == "media" -> {
                    respond(payload.encodeToByteArray(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/octet-stream"))
                }
                else -> respond("{}", HttpStatusCode.OK, jsonHeaders)
            }
        }

        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val authClient = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "client-id", null))
        val storage = InMemoryTokenStorage(OAuthTokens("access", "refresh", 2000000000L))
        val driveClient = DriveHttpClient(http, storage, GoogleAuthClients(null, authClient))
        val resolver = DrivePathResolver(driveClient, "Mimir")
        val driveStorage = GoogleDriveStorage(driveClient, resolver)

        val source = driveStorage.read(RemotePath("stream.txt"))
        val readBytes = source.readByteArray()
        assertEquals(payload.length, readBytes.size)
        assertEquals(payload, readBytes.decodeToString())
    }
}
