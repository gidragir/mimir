package dev.mimir.drive

import dev.mimir.core.RemoteAnomaly
import dev.mimir.core.RemoteAnomalyKind
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

class GoogleDriveStorageDuplicatesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun folder(
        id: String,
        name: String,
        parent: String,
        created: String = "2026-10-23T10:00:00.000Z",
        modified: String = created,
    ) = GoogleDriveFile(
        id = id,
        name = name,
        mimeType = DrivePathResolver.FOLDER_MIME_TYPE,
        modifiedTime = modified,
        createdTime = created,
        parents = listOf(parent),
    )

    private fun file(
        id: String,
        name: String,
        parent: String,
        modified: String = "2026-10-23T10:00:00.000Z",
        created: String = modified,
    ) = GoogleDriveFile(
        id = id,
        name = name,
        mimeType = "text/markdown",
        size = 3,
        modifiedTime = modified,
        createdTime = created,
        md5Checksum = "md5-$id",
        parents = listOf(parent),
    )

    private fun storage(items: List<GoogleDriveFile>): Pair<GoogleDriveStorage, Unit> {
        val allItems = listOf(folder("mimir_root", "Mimir", "my_drive")) + items
        val engine = MockEngine { request ->
            val q = request.url.parameters["q"]
            when {
                q?.contains("'root' in parents") == true ->
                    respond(
                        """{"files":[{"id":"mimir_root","name":"Mimir","mimeType":"application/vnd.google-apps.folder"}]}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                q == "trashed = false" ->
                    respond(
                        json.encodeToString(GoogleDriveFileList.serializer(), GoogleDriveFileList(allItems, null)),
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
        return GoogleDriveStorage(client, DrivePathResolver(client, "Mimir")) to Unit
    }

    @Test
    fun duplicateFoldersAreMergedUnderCanonical() = runTest {
        val items = listOf(
            folder("a1", "A", "mimir_root", created = "2026-10-01T00:00:00Z"),
            folder("a2", "A", "mimir_root", created = "2026-10-02T00:00:00Z"),
            file("x", "x.md", "a1"),
            file("y", "y.md", "a2"),
        )
        for (order in listOf(items, items.reversed())) {
            val (drive, _) = storage(order)
            assertEquals(setOf("A/x.md", "A/y.md"), drive.listAll().map { it.path.value }.toSet())
            assertEquals(listOf(RemoteAnomaly(RemotePath("A"), RemoteAnomalyKind.DuplicateFolder, 2)), drive.drainAnomalies())
            assertEquals(emptyList(), drive.drainAnomalies())
        }
    }

    @Test
    fun duplicateFilesAreDeterministic() = runTest {
        val items = listOf(
            file("f2", "n.md", "mimir_root", modified = "2026-10-23T10:00:00Z"),
            file("f1", "n.md", "mimir_root", modified = "2026-10-23T10:00:00Z"),
        )
        for (order in listOf(items, items.reversed())) {
            val (drive, _) = storage(order)
            assertEquals("md5-f1", drive.listAll().single().revision) // равный mtime → min id
            assertEquals(RemoteAnomalyKind.DuplicateFile, drive.drainAnomalies().single().kind)
        }
    }

    @Test
    fun sameNameFileInBothFolderCopiesIsOneEntryAndAnomaly() = runTest {
        val items = listOf(
            folder("a1", "A", "mimir_root", created = "2026-10-01T00:00:00Z"),
            folder("a2", "A", "mimir_root", created = "2026-10-02T00:00:00Z"),
            file("xOld", "x.md", "a1", modified = "2026-10-23T09:00:00Z"),
            file("xNew", "x.md", "a2", modified = "2026-10-23T12:00:00Z"),
        )
        for (order in listOf(items, items.reversed())) {
            val (drive, _) = storage(order)
            val entries = drive.listAll()
            assertEquals(1, entries.size)
            assertEquals("A/x.md", entries.single().path.value)
            assertEquals("md5-xNew", entries.single().revision)
            assertEquals(
                setOf(
                    RemoteAnomaly(RemotePath("A"), RemoteAnomalyKind.DuplicateFolder, 2),
                    RemoteAnomaly(RemotePath("A/x.md"), RemoteAnomalyKind.DuplicateFile, 2),
                ),
                drive.drainAnomalies().toSet(),
            )
        }
    }
}
