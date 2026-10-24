package dev.mimir.drive.path

import dev.mimir.core.RemotePath
import dev.mimir.drive.auth.GoogleAuthClient
import dev.mimir.drive.auth.GoogleAuthClients
import dev.mimir.drive.auth.GoogleClientCredentials
import dev.mimir.drive.auth.GoogleClientKind
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
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        val authClient = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "client-id", null))
        val storage = InMemoryTokenStorage(OAuthTokens("access", "refresh", 2000000000L))
        val client = DriveHttpClient(http, storage, GoogleAuthClients(null, authClient))
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
        val authClient = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "client-id", null))
        val storage = InMemoryTokenStorage(OAuthTokens("access", "refresh", 2000000000L))
        val client = DriveHttpClient(http, storage, GoogleAuthClients(null, authClient))
        val resolver = DrivePathResolver(client, rootFolderName = "Mimir")

        val folderId = resolver.resolveOrCreateFolderPath(RemotePath("new_dir"))
        assertEquals("created_dir_id", folderId)
        assertEquals(true, createdSubfolder)
    }

    @Test
    fun concurrentCreationOfSameFolderCreatesItOnce() = runTest {
        val created = mutableMapOf<String, String>()
        var postCount = 0
        val mockEngine = MockEngine { request ->
            val q = request.url.parameters["q"] ?: ""
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            // Приостанавливаем каждый запрос, чтобы оба вызова успели проверить «папки нет» до первого создания.
            yield()
            yield()
            when {
                q.contains("'root' in parents and name = 'Mimir'") ->
                    respond("""{"files":[{"id":"root_id","name":"Mimir","mimeType":"application/vnd.google-apps.folder"}]}""", HttpStatusCode.OK, jsonHeaders)
                request.method.value == "POST" -> {
                    val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    val name = Regex(""""name":"([^"]+)"""").find(body)!!.groupValues[1]
                    postCount++
                    val id = "id_${name}_$postCount"
                    created[name] = id
                    respond("""{"id":"$id","name":"$name","mimeType":"application/vnd.google-apps.folder"}""", HttpStatusCode.OK, jsonHeaders)
                }
                else -> {
                    val name = Regex("""name = '([^']+)'""").find(q)?.groupValues?.get(1)
                    val id = name?.let { created[it] }
                    if (id != null) {
                        respond("""{"files":[{"id":"$id","name":"$name","mimeType":"application/vnd.google-apps.folder"}]}""", HttpStatusCode.OK, jsonHeaders)
                    } else {
                        respond("""{"files":[]}""", HttpStatusCode.OK, jsonHeaders)
                    }
                }
            }
        }

        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val authClient = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "client-id", null))
        val storage = InMemoryTokenStorage(OAuthTokens("access", "refresh", 2000000000L))
        val client = DriveHttpClient(http, storage, GoogleAuthClients(null, authClient))
        val resolver = DrivePathResolver(client, rootFolderName = "Mimir")

        val ids = listOf(
            async { resolver.resolveOrCreateFolderPath(RemotePath("a/b")) },
            async { resolver.resolveOrCreateFolderPath(RemotePath("a/b")) },
        ).awaitAll()

        assertEquals(2, postCount, "one POST per segment, not per caller")
        assertEquals(ids[0], ids[1])
        assertTrue(ids[0].startsWith("id_b_"))
    }
}
