package dev.mimir.drive

import dev.mimir.core.ChangeFeedStorage
import dev.mimir.core.RemoteChange
import dev.mimir.core.RemoteChangesResult
import dev.mimir.core.RemoteEntry
import dev.mimir.core.RemotePath
import dev.mimir.drive.api.GoogleDriveChangeList
import dev.mimir.drive.api.GoogleDriveFile
import dev.mimir.drive.api.GoogleDriveFileList
import dev.mimir.drive.api.GoogleStartPageToken
import dev.mimir.drive.api.toRemoteEntry
import dev.mimir.drive.client.DriveHttpClient
import dev.mimir.drive.client.DriveHttpException
import dev.mimir.drive.path.DrivePathResolver
import dev.mimir.drive.upload.DriveUploader
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.io.Source
import kotlinx.serialization.Serializable

class GoogleDriveStorage(
    private val client: DriveHttpClient,
    private val pathResolver: DrivePathResolver,
    /** Если задан, файл качается кусками `Range` такого размера (для окружений без потокового тела). */
    downloadChunkBytes: Long? = null,
) : ChangeFeedStorage {

    private val downloader = DriveDownloader(client, pathResolver, downloadChunkBytes)
    private val uploader = DriveUploader(client, pathResolver)

    override suspend fun getLatestChangeToken(): String {
        val response = client.executeWithRetry { token ->
            client.httpClient.get("https://www.googleapis.com/drive/v3/changes/startPageToken") {
                header(HttpHeaders.Authorization, "Bearer $token")
                parameter("supportsAllDrives", "false")
            }
        }
        if (!response.status.isSuccess()) {
            throw DriveHttpException(response.status, "Failed to get start page token")
        }
        return response.body<GoogleStartPageToken>().startPageToken
    }

    override suspend fun getChanges(sinceToken: String): RemoteChangesResult {
        val changes = mutableListOf<RemoteChange>()
        var pageToken: String? = sinceToken
        var newStartToken: String? = null

        do {
            val response = client.executeWithRetry { token ->
                client.httpClient.get("https://www.googleapis.com/drive/v3/changes") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    parameter("pageToken", pageToken)
                    parameter("includeRemoved", "true")
                    parameter("supportsAllDrives", "false")
                    parameter("pageSize", 1000)
                    parameter("fields", "newStartPageToken,nextPageToken,changes(fileId,removed,file(id,name,mimeType,size,modifiedTime,md5Checksum,parents,trashed))")
                }
            }
            if (!response.status.isSuccess()) {
                throw DriveHttpException(response.status, "Failed to fetch changes for token: $pageToken")
            }

            val result = response.body<GoogleDriveChangeList>()
            newStartToken = result.newStartPageToken
            for (change in result.changes) {
                val file = change.file
                if (change.removed || file?.trashed == true) {
                    val path = pathResolver.resolvePathById(change.fileId)
                        ?: pathResolver.invalidateById(change.fileId)
                        ?: RemotePath(change.fileId)
                    changes.add(RemoteChange(path, isDeleted = true, entry = null))
                    pathResolver.invalidate(path)
                } else if (file != null) {
                    val path = pathResolver.resolveRemotePathForFile(file) ?: RemotePath(file.name)
                    changes.add(RemoteChange(path, isDeleted = false, entry = file.toRemoteEntry(path)))
                    pathResolver.registerPath(path, file)
                }
            }
            pageToken = result.nextPageToken
        } while (pageToken != null)

        return RemoteChangesResult(changes, newStartToken ?: sinceToken)
    }

    override suspend fun list(dir: RemotePath): List<RemoteEntry> {
        val folderId = resolveFolderId(dir) ?: return emptyList()
        val result = mutableListOf<RemoteEntry>()
        val registered = mutableListOf<Pair<RemotePath, GoogleDriveFile>>()
        var pageToken: String? = null

        do {
            val list = fetchFilesPage("'$folderId' in parents and trashed = false", 100, pageToken, "Failed to list folder $folderId")
            val deduplicated = list.files
                .filter { !it.trashed }
                .groupBy { it.name }
                .mapValues { (_, candidates) -> candidates.maxByOrNull { it.modifiedTime ?: "" } ?: candidates.first() }
                .values

            for (file in deduplicated) {
                val childPath = if (dir.value.isEmpty()) RemotePath(file.name) else RemotePath("${dir.value}/${file.name}")
                registered += childPath to file
                result += file.toRemoteEntry(childPath)
            }
            pageToken = list.nextPageToken
        } while (pageToken != null)

        pathResolver.registerPaths(registered)
        return result
    }

    /**
     * Весь видимый приложению набор файлов (`drive.file`) одним постраничным запросом без фильтра по родителю,
     * затем дерево строится в памяти от папки [root]. Всё, что не происходит от неё (чужие папки, сироты,
     * циклы), отбрасывается. Дубликаты имён в одной папке — как в [list]: остаётся самый свежий по `modifiedTime`.
     */
    override suspend fun listAll(root: RemotePath, onProgress: (folders: Int, files: Int) -> Unit): List<RemoteEntry> {
        val rootId = resolveFolderId(root) ?: return emptyList()
        // Кэши заполняются заново из этого обхода (registerPaths ниже). Пропавшее из списка
        // больше не отдаётся из кэша: stat пойдёт живым запросом.
        pathResolver.resetPathCaches()

        val children = HashMap<String, LinkedHashMap<String, GoogleDriveFile>>()
        var foldersSeen = 0
        var filesSeen = 0
        var pageToken: String? = null

        do {
            val page = fetchFilesPage("trashed = false", 1000, pageToken, "Failed to list Drive files")
            for (file in page.files) {
                if (file.trashed) continue
                if (file.isDirectory) foldersSeen++ else filesSeen++
                for (parent in file.parents) {
                    val byName = children.getOrPut(parent) { LinkedHashMap() }
                    val existing = byName[file.name]
                    if (existing == null || (file.modifiedTime ?: "") > (existing.modifiedTime ?: "")) {
                        byName[file.name] = file
                    }
                }
            }
            onProgress(foldersSeen, filesSeen)
            pageToken = page.nextPageToken
        } while (pageToken != null)

        val result = mutableListOf<RemoteEntry>()
        val registered = mutableListOf<Pair<RemotePath, GoogleDriveFile>>()
        val visited = hashSetOf(rootId)
        val queue = ArrayDeque<Pair<String, RemotePath>>()
        queue.addLast(rootId to root)
        var folders = 1

        while (queue.isNotEmpty()) {
            val (folderId, dir) = queue.removeFirst()
            val kids = children[folderId] ?: continue
            for (file in kids.values) {
                val childPath = if (dir.value.isEmpty()) RemotePath(file.name) else RemotePath("${dir.value}/${file.name}")
                if (file.isDirectory) {
                    if (!visited.add(file.id)) continue // цикл или уже обойдённая папка
                    folders++
                    registered += childPath to file
                    queue.addLast(file.id to childPath)
                } else {
                    registered += childPath to file
                    result += file.toRemoteEntry(childPath)
                }
            }
        }
        pathResolver.registerPaths(registered)
        onProgress(folders, result.size)
        return result
    }

    override suspend fun stat(path: RemotePath): RemoteEntry? =
        pathResolver.resolvePath(path)?.toRemoteEntry(path)

    override suspend fun read(path: RemotePath): Source = downloader.read(path)

    override suspend fun write(path: RemotePath, data: Source): RemoteEntry = uploader.upload(path, data)

    /** Файл переносится в корзину Drive, а не стирается: ошибочное удаление можно отменить в течение 30 дней. */
    override suspend fun delete(path: RemotePath) {
        val file = pathResolver.resolvePath(path) ?: return
        val response = client.executeWithRetry { token ->
            client.httpClient.patch("$FILES_URL/${file.id}") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(TrashRequest(trashed = true))
            }
        }
        if (!response.status.isSuccess() && response.status.value != 404) {
            throw DriveHttpException(response.status, "Failed to delete file ${file.id}")
        }
        pathResolver.invalidate(path)
        pathResolver.invalidateById(file.id)
    }

    private suspend fun resolveFolderId(dir: RemotePath): String? {
        if (dir.value.isEmpty()) return pathResolver.resolveRootFolderId()
        val folder = pathResolver.resolvePath(dir) ?: return null
        return folder.id.takeIf { folder.isDirectory }
    }

    private suspend fun fetchFilesPage(
        query: String,
        pageSize: Int,
        pageToken: String?,
        errorMessage: String,
    ): GoogleDriveFileList {
        val response = client.executeWithRetry { token ->
            client.httpClient.get(FILES_URL) {
                header(HttpHeaders.Authorization, "Bearer $token")
                parameter("q", query)
                parameter("fields", FILE_LIST_FIELDS)
                parameter("pageSize", pageSize)
                if (pageToken != null) parameter("pageToken", pageToken)
            }
        }
        if (!response.status.isSuccess()) {
            throw DriveHttpException(response.status, errorMessage)
        }
        return response.body()
    }

    @Serializable
    private data class TrashRequest(val trashed: Boolean)

    private companion object {
        const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
        const val FILE_LIST_FIELDS = "nextPageToken,files(id,name,mimeType,size,modifiedTime,md5Checksum,parents,trashed)"
    }
}
