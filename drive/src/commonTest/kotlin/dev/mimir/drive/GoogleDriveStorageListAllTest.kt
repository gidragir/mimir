package dev.mimir.drive

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
import dev.mimir.drive.client.DriveHttpException
import dev.mimir.drive.path.DrivePathResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class GoogleDriveStorageListAllTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun folder(id: String, name: String, parent: String, modified: String = "2026-10-23T10:00:00.000Z") =
        GoogleDriveFile(id, name, DrivePathResolver.FOLDER_MIME_TYPE, modifiedTime = modified, parents = listOf(parent))

    private fun file(id: String, name: String, parent: String, modified: String = "2026-10-23T10:00:00.000Z", trashed: Boolean = false) =
        GoogleDriveFile(id, name, "text/markdown", size = 3, modifiedTime = modified, md5Checksum = "md5-$id", parents = listOf(parent), trashed = trashed)

    private val tree = listOf(
        folder("mimir_root", "Mimir", "my_drive"),
        folder("fA", "a", "mimir_root"),
        folder("fB", "b", "fA"),
        file("deep", "deep.md", "fB"),
        file("x", "x.md", "fA"),
        file("top", "top.md", "mimir_root"),
        folder("empty", "empty", "mimir_root"),
        file("dupOld", "dup.md", "mimir_root", modified = "2026-10-23T09:00:00.000Z"),
        file("dupNew", "dup.md", "mimir_root", modified = "2026-10-23T12:00:00.000Z"),
        file("dupOlder", "dup.md", "mimir_root", modified = "2026-10-22T12:00:00.000Z"),
        file("trashedOne", "trashed.md", "mimir_root", trashed = true),
        // вне хранилища: сирота без известного родителя и файл в чужой папке
        file("orphan", "orphan.md", "unknown_parent"),
        folder("otherApp", "Other", "my_drive"),
        file("outside", "outside.md", "otherApp"),
        // цикл, недостижимый из корня
        folder("c1", "c1", "c2"),
        folder("c2", "c2", "c1"),
        file("inCycle", "cyc.md", "c1"),
    )

    private class Recorder {
        val requests = mutableListOf<HttpRequestData>()
        val listAllRequests get() = requests.filter { it.url.parameters["q"] == "trashed = false" }
    }

    private fun storage(
        items: List<GoogleDriveFile>,
        pageSize: Int? = null,
        recorder: Recorder = Recorder(),
        failAll: Boolean = false,
    ): Pair<GoogleDriveStorage, Recorder> {
        val engine = MockEngine { request ->
            recorder.requests += request
            handle(request, items, pageSize, failAll)
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(json) } }
        val auth = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "client-id", null))
        val tokens = InMemoryTokenStorage(OAuthTokens("access", "refresh", 2000000000L))
        val client = DriveHttpClient(http, tokens, GoogleAuthClients(null, auth))
        return GoogleDriveStorage(client, DrivePathResolver(client, "Mimir")) to recorder
    }

    private fun MockRequestHandleScope.handle(
        request: HttpRequestData,
        items: List<GoogleDriveFile>,
        pageSize: Int?,
        failAll: Boolean,
    ): HttpResponseData {
        val q = request.url.parameters["q"]
        return when {
            q?.contains("'root' in parents") == true ->
                respond("""{"files":[{"id":"mimir_root","name":"Mimir","mimeType":"application/vnd.google-apps.folder"}]}""", HttpStatusCode.OK, jsonHeaders)
            q == "trashed = false" -> {
                if (failAll) return respond("""{"error":"boom"}""", HttpStatusCode.Forbidden, jsonHeaders)
                val size = pageSize ?: request.url.parameters["pageSize"]!!.toInt()
                val offset = request.url.parameters["pageToken"]?.toInt() ?: 0
                val page = items.drop(offset).take(size)
                val next = (offset + size).takeIf { it < items.size }?.toString()
                respond(json.encodeToString(GoogleDriveFileList.serializer(), GoogleDriveFileList(page, next)), HttpStatusCode.OK, jsonHeaders)
            }
            q?.contains("'mimir_root' in parents and name = 'a'") == true ->
                respond(json.encodeToString(GoogleDriveFileList.serializer(), GoogleDriveFileList(listOf(folder("fA", "a", "mimir_root")))), HttpStatusCode.OK, jsonHeaders)
            request.url.parameters["alt"] == "media" ->
                respond("hey".encodeToByteArray(), HttpStatusCode.OK)
            else -> respond("""{"files":[]}""", HttpStatusCode.OK, jsonHeaders)
        }
    }

    @Test
    fun multiPageTreeYieldsOnlyVaultFilesWithCorrectPaths() = runTest {
        val (drive, rec) = storage(tree, pageSize = 4)

        val all = drive.listAll()

        assertEquals(
            setOf("a/b/deep.md", "a/x.md", "top.md", "dup.md"),
            all.map { it.path.value }.toSet(),
        )
        assertEquals(4, all.size)
        assertTrue(all.none { it.isDirectory })
        val dup = all.single { it.path.value == "dup.md" }
        assertEquals("md5-dupNew", dup.revision)
        assertEquals(5, rec.listAllRequests.size) // 17 элементов по 4 на страницу
        val first = rec.listAllRequests.first()
        assertEquals("1000", first.url.parameters["pageSize"])
        assertEquals("nextPageToken,files(id,name,mimeType,size,modifiedTime,md5Checksum,parents,trashed)", first.url.parameters["fields"])
        assertEquals(null, first.url.parameters["pageToken"])
        assertEquals("4", rec.listAllRequests[1].url.parameters["pageToken"])
    }

    @Test
    fun listedPathsAreRegisteredSoStatAndReadNeedNoResolution() = runTest {
        val (drive, rec) = storage(tree, pageSize = 4)
        drive.listAll()
        val before = rec.requests.size

        val stat = assertNotNull(drive.stat(RemotePath("a/b/deep.md")))
        assertEquals("md5-deep", stat.revision)
        assertNotNull(drive.stat(RemotePath("a/b")))
        assertEquals(before, rec.requests.size, "stat after listAll must hit the caches")

        assertEquals("hey", drive.read(RemotePath("a/b/deep.md")).readByteArray().decodeToString())
        assertEquals(before + 1, rec.requests.size, "read after listAll must only download")
        assertTrue(rec.requests.last().url.encodedPath.endsWith("/files/deep"))
    }

    @Test
    fun fileGoneFromNewListingIsNotAStaleCacheHitForStat() = runTest {
        val items = tree.toMutableList()
        val (drive, rec) = storage(items)
        drive.listAll()
        assertNotNull(drive.stat(RemotePath("top.md")))

        // между прогонами top.md удалили в Drive: свежий список его уже не содержит
        items.removeAll { it.id == "top" }
        drive.listAll()
        val before = rec.requests.size

        assertEquals(null, drive.stat(RemotePath("top.md")), "stat must not return a stale cached entry")
        val live = rec.requests.drop(before).map { it.url.parameters["q"] }
        assertTrue(
            live.any { it != null && it.contains("'mimir_root' in parents") && it.contains("name = 'top.md'") },
            "stat must fall through to a live lookup: $live",
        )

        // а то, что есть в свежем списке, по-прежнему берётся из кэша без запросов
        val afterMiss = rec.requests.size
        assertNotNull(drive.stat(RemotePath("a/b/deep.md")))
        assertNotNull(drive.stat(RemotePath("a/x.md")))
        assertEquals(afterMiss, rec.requests.size, "listed paths must still hit the caches")
    }

    @Test
    fun folderGoneFromNewListingIsNotAStaleCacheHitForStat() = runTest {
        val items = tree.toMutableList()
        val (drive, _) = storage(items)
        drive.listAll()

        items.removeAll { it.id in setOf("fB", "deep") }
        drive.listAll()

        assertEquals(null, drive.stat(RemotePath("a/b/deep.md")))
    }

    @Test
    fun requestsExactlyCeilOfNOver1000Pages() = runTest {
        val many = buildList {
            add(folder("mimir_root", "Mimir", "my_drive"))
            add(folder("sub", "sub", "mimir_root"))
            for (i in 0 until 2498) add(file("f$i", "n$i.md", if (i % 2 == 0) "mimir_root" else "sub"))
        }
        val (drive, rec) = storage(many)
        val progress = mutableListOf<Pair<Int, Int>>()

        val all = drive.listAll { folders, files -> progress += folders to files }

        assertEquals(2498, all.size)
        assertEquals(3, rec.listAllRequests.size) // ceil(2500 / 1000)
        assertTrue(progress.size >= 3, "progress per page: $progress")
        assertEquals(progress.map { it.second }.sorted(), progress.map { it.second })
        assertEquals(2 to 2498, progress.last()) // корень + sub
        assertTrue(all.count { it.path.value.startsWith("sub/") } == 1249)
    }

    @Test
    fun cycleReachableFromRootIsCutAndEveryFileListedOnce() = runTest(timeout = 10.seconds) {
        // a -> b -> loop, а у a второй родитель — loop: обход от корня вернулся бы в предка
        val cyclic = listOf(
            folder("mimir_root", "Mimir", "my_drive"),
            GoogleDriveFile("fA", "a", DrivePathResolver.FOLDER_MIME_TYPE, modifiedTime = "2026-10-23T10:00:00.000Z", parents = listOf("mimir_root", "loop")),
            folder("fB", "b", "fA"),
            folder("loop", "loop", "fB"),
            file("deep", "deep.md", "fB"),
            file("inLoop", "l.md", "loop"),
            file("x", "x.md", "fA"),
            file("top", "top.md", "mimir_root"),
        )
        val (drive, _) = storage(cyclic, pageSize = 3)

        val paths = drive.listAll().map { it.path.value }

        assertEquals(listOf("a/b/deep.md", "a/b/loop/l.md", "a/x.md", "top.md").sorted(), paths.sorted())
    }

    @Test
    fun subtreeRootReturnsOnlyItsDescendants() = runTest {
        val (drive, _) = storage(tree)
        val all = drive.listAll(RemotePath("a"))
        assertEquals(setOf("a/b/deep.md", "a/x.md"), all.map { it.path.value }.toSet())
    }

    @Test
    fun listAllFailureIsDriveHttpException() = runTest {
        val (drive, _) = storage(tree, failAll = true)
        assertFailsWith<DriveHttpException> { drive.listAll() }
    }

    @Test
    fun listKeepsWorkingForDirectChildren() = runTest {
        val (drive, _) = storage(tree)
        // `list` по-прежнему ходит запросом с фильтром по родителю (мок отвечает пустым списком)
        assertEquals(emptyList(), drive.list(RemotePath("")))
    }
}
