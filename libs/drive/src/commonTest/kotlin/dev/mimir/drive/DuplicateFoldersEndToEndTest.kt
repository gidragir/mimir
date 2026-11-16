package dev.mimir.drive

import dev.mimir.core.EncryptedRemoteStorage
import dev.mimir.core.LocalVault
import dev.mimir.core.RemoteAnomalyKind
import dev.mimir.core.RemotePath
import dev.mimir.core.VaultFileId
import dev.mimir.core.sync.DefaultSyncEngine
import dev.mimir.core.sync.FileSyncRecord
import dev.mimir.core.sync.InMemorySyncStateStore
import dev.mimir.core.sync.SyncIndex
import dev.mimir.core.testing.CountingLocalVault
import dev.mimir.core.testing.InMemoryLocalVault
import dev.mimir.core.testing.TestCiphers
import dev.mimir.core.testing.TestClock
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
import kotlinx.io.Buffer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class DuplicateFoldersEndToEndTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun fileInNonCanonicalCopyIsNotDeletedLocally() = runTest {
        val cipher = TestCiphers.forPassword("test-password")
        fun enc(name: String): String = cipher.encryptName(name)

        val now = Instant.parse("2026-10-23T10:00:00Z")
        val clock = TestClock(now)
        val rawVault = InMemoryLocalVault(clock)
        val countingVault = CountingLocalVault(rawVault)
        var deleteCalls = 0
        val localVault = object : LocalVault by countingVault {
            override suspend fun delete(id: VaultFileId) {
                deleteCalls++
                countingVault.delete(id)
            }
        }

        val plainBytes = "hello".encodeToByteArray()
        val encryptedSize = 37L
        val localEntry = localVault.write("A/y.md", Buffer().apply { write(plainBytes) })

        val stateStore = InMemorySyncStateStore(
            SyncIndex(
                records = mapOf(
                    "A/y.md" to FileSyncRecord(
                        relativePath = "A/y.md",
                        localId = localEntry.id,
                        baseHash = "dummy",
                        localSize = localEntry.size,
                        localModifiedAt = localEntry.modifiedAt,
                        remotePath = RemotePath("A/y.md"),
                        remoteRevision = "md5-y",
                        remoteModifiedAt = now,
                        remoteSize = encryptedSize,
                    ),
                ),
            ),
        )

        val items = listOf(
            GoogleDriveFile("mimir_root", "Mimir", DrivePathResolver.FOLDER_MIME_TYPE, createdTime = "2026-10-01T00:00:00Z", parents = listOf("my_drive")),
            GoogleDriveFile("a1", enc("A"), DrivePathResolver.FOLDER_MIME_TYPE, createdTime = "2026-10-01T00:00:00Z", parents = listOf("mimir_root")),
            GoogleDriveFile("a2", enc("A"), DrivePathResolver.FOLDER_MIME_TYPE, createdTime = "2026-10-02T00:00:00Z", parents = listOf("mimir_root")),
            GoogleDriveFile("y", enc("y.md"), "application/octet-stream", size = encryptedSize, modifiedTime = "2026-10-23T10:00:00Z", md5Checksum = "md5-y", parents = listOf("a2")),
        )

        val engine = MockEngine { request ->
            val q = request.url.parameters["q"] ?: ""
            when {
                request.url.encodedPath.endsWith("/changes/startPageToken") ->
                    respond("""{"startPageToken":"1"}""", HttpStatusCode.OK, jsonHeaders)
                q.contains("'root' in parents") ->
                    respond("""{"files":[{"id":"mimir_root","name":"Mimir","mimeType":"application/vnd.google-apps.folder","createdTime":"2026-10-01T00:00:00Z"}]}""", HttpStatusCode.OK, jsonHeaders)
                q == "trashed = false" ->
                    respond(json.encodeToString(GoogleDriveFileList.serializer(), GoogleDriveFileList(items, null)), HttpStatusCode.OK, jsonHeaders)
                else -> respond("""{"files":[]}""", HttpStatusCode.OK, jsonHeaders)
            }
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(json) } }
        val auth = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "client-id", null))
        val tokens = InMemoryTokenStorage(OAuthTokens("access", "refresh", 2000000000L))
        val client = DriveHttpClient(http, tokens, GoogleAuthClients(null, auth))

        val drive = GoogleDriveStorage(client, DrivePathResolver(client, "Mimir"))
        val encrypted = EncryptedRemoteStorage(drive, cipher)
        val syncEngine = DefaultSyncEngine(
            localVault = localVault,
            remoteStorage = encrypted,
            stateStore = stateStore,
            clock = clock,
        )

        syncEngine.syncNow()

        assertTrue(localVault.list().any { it.relativePath == "A/y.md" })
        assertEquals(0, deleteCalls)
        assertEquals(RemoteAnomalyKind.DuplicateFolder, syncEngine.progress.value!!.anomalies.single().kind)
    }
}
