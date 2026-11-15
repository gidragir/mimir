package dev.mimir.drive

import dev.mimir.core.RemotePath
import dev.mimir.drive.api.GoogleDriveFile
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
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoogleDriveChangePeekTest {
    private fun createDrive(mockEngine: MockEngine): Pair<GoogleDriveStorage, DrivePathResolver> {
        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val authClient = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "client-id", null))
        val storage = InMemoryTokenStorage(OAuthTokens("access", "refresh", 2000000000L))
        val driveClient = DriveHttpClient(http, storage, GoogleAuthClients(null, authClient))
        val resolver = DrivePathResolver(driveClient, "Mimir")
        return GoogleDriveStorage(driveClient, resolver) to resolver
    }

    @Test
    fun peekDoesNotTouchPathCache() = runTest {
        val mockEngine = MockEngine { request ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                request.url.encodedPath == "/drive/v3/changes" && request.url.parameters["pageToken"] == "t1" -> {
                    respond(
                        """
                        {
                            "newStartPageToken": "t2",
                            "changes": [
                                {
                                    "fileId": "known",
                                    "removed": true
                                }
                            ]
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }
                else -> respond("""{"files":[]}""", HttpStatusCode.OK, jsonHeaders)
            }
        }
        val (drive, resolver) = createDrive(mockEngine)
        resolver.registerPath(RemotePath("docs/old.md"), GoogleDriveFile("known", "old.md", "text/markdown"))

        val peek = drive.peekChanges("t1")
        assertTrue(peek.hasChanges)
        assertEquals("t2", peek.nextToken)
        assertEquals(RemotePath("docs/old.md"), resolver.resolvePathById("known"))
    }

    @Test
    fun ownUploadIsEchoButForeignDeleteOfSameFileIsNot() = runTest {
        val mockEngine = MockEngine { request ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                request.url.parameters["q"]?.contains("'root' in parents") == true -> {
                    respond(
                        """{"files":[{"id":"mimir_root","name":"Mimir","mimeType":"application/vnd.google-apps.folder"}]}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }
                request.url.encodedPath == "/upload/drive/v3/files" -> {
                    respond(
                        """{"id":"up1","name":"n.md","mimeType":"text/markdown","size":"2","md5Checksum":"m1"}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }
                request.url.encodedPath == "/drive/v3/changes" && request.url.parameters["pageToken"] == "t1" -> {
                    respond(
                        """
                        {
                            "newStartPageToken": "t2",
                            "changes": [
                                {
                                    "fileId": "up1",
                                    "removed": false,
                                    "file": {
                                        "md5Checksum": "m1",
                                        "trashed": false,
                                        "mimeType": "text/markdown"
                                    }
                                }
                            ]
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }
                request.url.encodedPath == "/drive/v3/changes" && request.url.parameters["pageToken"] == "t2" -> {
                    respond(
                        """
                        {
                            "newStartPageToken": "t3",
                            "changes": [
                                {
                                    "fileId": "up1",
                                    "removed": true
                                }
                            ]
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }
                else -> respond("""{"files":[]}""", HttpStatusCode.OK, jsonHeaders)
            }
        }
        val (drive, _) = createDrive(mockEngine)

        drive.write(RemotePath("n.md"), Buffer().apply { writeString("hi") })
        assertFalse(drive.peekChanges("t1").hasChanges)
        assertTrue(drive.peekChanges("t2").hasChanges)
    }
}
