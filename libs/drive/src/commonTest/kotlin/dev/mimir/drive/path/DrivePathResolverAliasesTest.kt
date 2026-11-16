package dev.mimir.drive.path

import dev.mimir.core.RemotePath
import dev.mimir.drive.api.GoogleDriveFile
import dev.mimir.drive.api.GoogleDriveFileList
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DrivePathResolverAliasesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun statFindsFileInNonCanonicalFolderCopyOnColdCache() = runTest {
        val queries = mutableListOf<String>()
        val engine = MockEngine { request ->
            val q = request.url.parameters["q"] ?: ""
            queries += q
            when {
                q.contains("'root' in parents") && q.contains("name = 'Mimir'") ->
                    respond(
                        """{"files":[{"id":"mimir_root","name":"Mimir","mimeType":"application/vnd.google-apps.folder","createdTime":"2026-10-01T00:00:00Z"}]}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                q.contains("'mimir_root' in parents") && q.contains("name = 'A'") ->
                    respond(
                        json.encodeToString(
                            GoogleDriveFileList.serializer(),
                            GoogleDriveFileList(
                                listOf(
                                    GoogleDriveFile("a1", "A", DrivePathResolver.FOLDER_MIME_TYPE, createdTime = "2026-10-01T00:00:00Z", parents = listOf("mimir_root")),
                                    GoogleDriveFile("a2", "A", DrivePathResolver.FOLDER_MIME_TYPE, createdTime = "2026-10-02T00:00:00Z", parents = listOf("mimir_root")),
                                ),
                            ),
                        ),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                q.contains("name = 'x.md'") && q.contains("'a1' in parents") && q.contains("'a2' in parents") ->
                    respond(
                        json.encodeToString(
                            GoogleDriveFileList.serializer(),
                            GoogleDriveFileList(
                                listOf(
                                    GoogleDriveFile("x", "x.md", "text/markdown", size = 5, modifiedTime = "2026-10-23T10:00:00Z", md5Checksum = "md5-x", parents = listOf("a2")),
                                ),
                            ),
                        ),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                else -> respond("""{"files":[]}""", HttpStatusCode.OK, jsonHeaders)
            }
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(json) } }
        val auth = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "client-id", null))
        val tokens = InMemoryTokenStorage(OAuthTokens("access", "refresh", 2000000000L))
        val client = DriveHttpClient(http, tokens, GoogleAuthClients(null, auth))

        val resolver = DrivePathResolver(client, "Mimir") // кэш пуст
        val file = assertNotNull(resolver.resolvePath(RemotePath("A/x.md")))
        assertEquals("x", file.id)
        assertTrue(queries.last().contains("'a1' in parents or 'a2' in parents"), "Actual query: ${queries.last()}")
    }
}
