package dev.mimir.drive

import dev.mimir.core.ChangeFeedStorage
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
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoogleDriveChangeFeedTest {
    @Test
    fun testChangeFeedFetchChangesAndMapToRemotePath() = runTest {
        val mockEngine = MockEngine { request ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                request.url.parameters["q"]?.contains("'root' in parents") == true -> {
                    respond("""{"files":[{"id":"mimir_root","name":"Mimir","mimeType":"application/vnd.google-apps.folder"}]}""", HttpStatusCode.OK, jsonHeaders)
                }
                request.url.encodedPath == "/drive/v3/changes/startPageToken" -> {
                    respond("""{"startPageToken":"start_token_1"}""", HttpStatusCode.OK, jsonHeaders)
                }
                request.url.encodedPath == "/drive/v3/changes" && request.url.parameters["pageToken"] == "start_token_1" -> {
                    respond("""
                        {
                            "newStartPageToken": "start_token_2",
                            "changes": [
                                {
                                    "fileId": "file_ch1",
                                    "removed": false,
                                    "file": {
                                        "id": "file_ch1",
                                        "name": "updated.md",
                                        "mimeType": "text/markdown",
                                        "size": "42",
                                        "modifiedTime": "2026-10-23T14:00:00.000Z",
                                        "md5Checksum": "chk123",
                                        "parents": ["mimir_root"],
                                        "trashed": false
                                    }
                                },
                                {
                                    "fileId": "file_ch2",
                                    "removed": true
                                }
                            ]
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
        val driveStorage = GoogleDriveStorage(driveClient, resolver)

        val feedStorage = driveStorage as ChangeFeedStorage
        val token = feedStorage.getLatestChangeToken()
        assertEquals("start_token_1", token)

        val changes = feedStorage.getChanges(token)
        assertEquals("start_token_2", changes.nextToken)
        assertEquals(2, changes.changes.size)
        assertEquals(RemotePath("updated.md"), changes.changes[0].path)
        assertEquals(false, changes.changes[0].isDeleted)
        assertTrue(changes.changes[1].isDeleted)
    }

    @Test
    fun testChangeFeedResolvesNestedPathAndKnownDeletedPath() = runTest {
        val mockEngine = MockEngine { request ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                request.url.parameters["q"]?.contains("'root' in parents") == true -> {
                    respond("""{"files":[{"id":"mimir_root","name":"Mimir","mimeType":"application/vnd.google-apps.folder"}]}""", HttpStatusCode.OK, jsonHeaders)
                }
                request.url.encodedPath == "/drive/v3/changes" -> {
                    respond("""
                        {
                            "newStartPageToken": "tok2",
                            "changes": [
                                {
                                    "fileId": "nested_file",
                                    "removed": false,
                                    "file": {
                                        "id": "nested_file",
                                        "name": "child.md",
                                        "mimeType": "text/markdown",
                                        "parents": ["subfolder_id"],
                                        "trashed": false
                                    }
                                },
                                {
                                    "fileId": "known_deleted_id",
                                    "removed": true
                                }
                            ]
                        }
                    """.trimIndent(), HttpStatusCode.OK, jsonHeaders)
                }
                request.url.encodedPath == "/drive/v3/files/subfolder_id" -> {
                    respond("""
                        {
                            "id": "subfolder_id",
                            "name": "subfolder",
                            "mimeType": "application/vnd.google-apps.folder",
                            "parents": ["mimir_root"],
                            "trashed": false
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
        // Предварительно регистрируем известный файл
        resolver.registerPath(
            RemotePath("docs/old.md"),
            dev.mimir.drive.api.GoogleDriveFile(id = "known_deleted_id", name = "old.md", mimeType = "text/markdown")
        )

        val driveStorage = GoogleDriveStorage(driveClient, resolver)
        val changes = driveStorage.getChanges("tok1")

        assertEquals(2, changes.changes.size)
        // Вложенный файл восстановил путь через родительскую папку
        assertEquals(RemotePath("subfolder/child.md"), changes.changes[0].path)
        assertEquals(false, changes.changes[0].isDeleted)
        // Удаленный файл восстановил свой зарегистрированный путь
        assertEquals(RemotePath("docs/old.md"), changes.changes[1].path)
        assertTrue(changes.changes[1].isDeleted)
    }
}
