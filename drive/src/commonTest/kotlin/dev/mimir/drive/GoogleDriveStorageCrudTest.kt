package dev.mimir.drive

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
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GoogleDriveStorageCrudTest {
    @Test
    fun testListAndReadOperations() = runTest {
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
                                    "id": "file_123",
                                    "name": "hello.txt",
                                    "mimeType": "text/plain",
                                    "size": "11",
                                    "modifiedTime": "2026-10-23T12:00:00.000Z",
                                    "md5Checksum": "hash123",
                                    "trashed": false
                                }
                            ]
                        }
                    """.trimIndent(), HttpStatusCode.OK, jsonHeaders)
                }
                request.url.encodedPath == "/drive/v3/files/file_123" && request.url.parameters["alt"] == "media" -> {
                    respond("Hello World".encodeToByteArray(), HttpStatusCode.OK)
                }
                else -> respond("{}", HttpStatusCode.OK, jsonHeaders)
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

        val entries = driveStorage.list(RemotePath(""))
        assertEquals(1, entries.size)
        assertEquals(RemotePath("hello.txt"), entries[0].path)
        assertEquals(11L, entries[0].size)

        val statEntry = driveStorage.stat(RemotePath("hello.txt"))
        assertNotNull(statEntry)
        assertEquals("hash123", statEntry.revision)

        val content = driveStorage.read(RemotePath("hello.txt")).readByteArray()
        assertEquals("Hello World", content.decodeToString())
    }

    @Test
    fun testDeleteOperation() = runTest {
        var deletedFileId: String? = null
        val mockEngine = MockEngine { request ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                request.url.parameters["q"]?.contains("'root' in parents") == true -> {
                    respond("""{"files":[{"id":"mimir_root","name":"Mimir","mimeType":"application/vnd.google-apps.folder"}]}""", HttpStatusCode.OK, jsonHeaders)
                }
                request.url.parameters["q"]?.contains("name = 'delete_me.txt'") == true -> {
                    respond("""
                        {
                            "files": [
                                {
                                    "id": "file_del_1",
                                    "name": "delete_me.txt",
                                    "mimeType": "text/plain",
                                    "size": "5",
                                    "modifiedTime": "2026-10-23T12:00:00.000Z",
                                    "trashed": false
                                }
                            ]
                        }
                    """.trimIndent(), HttpStatusCode.OK, jsonHeaders)
                }
                request.url.encodedPath == "/drive/v3/files/file_del_1" && request.method.value == "DELETE" -> {
                    deletedFileId = "file_del_1"
                    respond("", HttpStatusCode.NoContent)
                }
                else -> respond("{}", HttpStatusCode.OK, jsonHeaders)
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

        driveStorage.delete(RemotePath("delete_me.txt"))
        assertEquals("file_del_1", deletedFileId)
    }

    @Test
    fun testListDeduplicatesDuplicateNamesKeepingMostRecent() = runTest {
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
                                    "id": "file_old",
                                    "name": "duplicate.txt",
                                    "mimeType": "text/plain",
                                    "size": "5",
                                    "modifiedTime": "2026-10-23T10:00:00.000Z",
                                    "md5Checksum": "old_hash",
                                    "trashed": false
                                },
                                {
                                    "id": "file_new",
                                    "name": "duplicate.txt",
                                    "mimeType": "text/plain",
                                    "size": "15",
                                    "modifiedTime": "2026-10-23T12:00:00.000Z",
                                    "md5Checksum": "new_hash",
                                    "trashed": false
                                }
                            ]
                        }
                    """.trimIndent(), HttpStatusCode.OK, jsonHeaders)
                }
                else -> respond("{}", HttpStatusCode.OK, jsonHeaders)
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

        val entries = driveStorage.list(RemotePath(""))
        assertEquals(1, entries.size)
        assertEquals(RemotePath("duplicate.txt"), entries[0].path)
        assertEquals(15L, entries[0].size)
        assertEquals("new_hash", entries[0].revision)
    }
}
