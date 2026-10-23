package dev.mimir.drive.path

import dev.mimir.core.RemotePath
import dev.mimir.drive.auth.GoogleAuthClient
import dev.mimir.drive.auth.InMemoryTokenStorage
import dev.mimir.drive.auth.OAuthTokens
import dev.mimir.drive.client.DriveHttpClient
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

class DrivePathResolverTest {
    @Test
    fun testResolvesExistingNestedPathAndPicksLatestDuplicate() = runTest {
        val mockEngine = MockEngine { request ->
            val q = request.url.parameters["q"] ?: ""
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                q.contains("'root' in parents and name = 'Mimir'") -> {
                    respond("""{"files":[{"id":"root_id","name":"Mimir","mimeType":"application/vnd.google-apps.folder"}]}""", HttpStatusCode.OK, jsonHeaders)
                }
                q.contains("'root_id' in parents and name = 'folder'") -> {
                    respond("""{"files":[{"id":"folder_id","name":"folder","mimeType":"application/vnd.google-apps.folder"}]}""", HttpStatusCode.OK, jsonHeaders)
                }
                q.contains("'folder_id' in parents and name = 'note.md'") -> {
                    // Возвращаем дубликат: старый и новый
                    respond("""
                        {
                            "files": [
                                {"id":"old_file","name":"note.md","mimeType":"text/markdown","modifiedTime":"2026-10-23T10:00:00Z"},
                                {"id":"new_file","name":"note.md","mimeType":"text/markdown","modifiedTime":"2026-10-23T12:00:00Z"}
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
        val client = DriveHttpClient(http, storage, authClient)
        val resolver = DrivePathResolver(client, rootFolderName = "Mimir")

        val resolved = resolver.resolvePath(RemotePath("folder/note.md"))
        assertEquals("new_file", resolved?.id)
    }

    @Test
    fun testCreatesFolderHierarchyWhenMissing() = runTest {
        var createdSubfolder = false
        val mockEngine = MockEngine { request ->
            val q = request.url.parameters["q"] ?: ""
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                q.contains("'root' in parents and name = 'Mimir'") -> {
                    respond("""{"files":[{"id":"root_id","name":"Mimir","mimeType":"application/vnd.google-apps.folder"}]}""", HttpStatusCode.OK, jsonHeaders)
                }
                q.contains("'root_id' in parents and name = 'new_dir'") -> {
                    respond("""{"files":[]}""", HttpStatusCode.OK, jsonHeaders)
                }
                request.url.encodedPath == "/drive/v3/files" && request.method.value == "POST" -> {
                    createdSubfolder = true
                    respond("""{"id":"created_dir_id","name":"new_dir","mimeType":"application/vnd.google-apps.folder"}""", HttpStatusCode.OK, jsonHeaders)
                }
                else -> respond("""{"files":[]}""", HttpStatusCode.OK, jsonHeaders)
            }
        }

        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val authClient = GoogleAuthClient(http, "client-id")
        val storage = InMemoryTokenStorage(OAuthTokens("access", "refresh", 2000000000L))
        val client = DriveHttpClient(http, storage, authClient)
        val resolver = DrivePathResolver(client, rootFolderName = "Mimir")

        val folderId = resolver.resolveOrCreateFolderPath(RemotePath("new_dir"))
        assertEquals("created_dir_id", folderId)
        assertEquals(true, createdSubfolder)
    }
}
