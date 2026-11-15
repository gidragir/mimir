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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ChangeFeedTwoConsumersTest {
    @Test
    fun schedulerPeekThenEngineGetChangesStillAppliesRemoval() = runTest {
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
                request.url.parameters["q"] == "trashed = false" -> {
                    respond(
                        """
                        {
                            "files": [
                                {
                                    "id": "f1",
                                    "name": "a.md",
                                    "mimeType": "text/markdown",
                                    "size": "3",
                                    "modifiedTime": "2026-10-23T14:00:00.000Z",
                                    "md5Checksum": "chk1",
                                    "parents": ["mimir_root"],
                                    "trashed": false
                                }
                            ]
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }
                request.url.encodedPath == "/drive/v3/changes" && request.url.parameters["pageToken"] == "tok" -> {
                    respond(
                        """
                        {
                            "newStartPageToken": "tok2",
                            "changes": [
                                {
                                    "fileId": "f1",
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
        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val authClient = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "client-id", null))
        val storage = InMemoryTokenStorage(OAuthTokens("access", "refresh", 2000000000L))
        val driveClient = DriveHttpClient(http, storage, GoogleAuthClients(null, authClient))
        val resolver = DrivePathResolver(driveClient, "Mimir")
        val drive = GoogleDriveStorage(driveClient, resolver)

        drive.listAll()
        drive.peekChanges("tok")
        val res = drive.getChanges("tok")
        assertEquals(RemotePath("a.md"), res.changes.single { it.isDeleted }.path)
    }
}
