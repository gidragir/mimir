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
import dev.mimir.drive.client.DriveHttpClient
import dev.mimir.drive.client.DriveHttpException
import dev.mimir.drive.path.DrivePathResolver
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
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

class GoogleDriveStorage(
    private val client: DriveHttpClient? = null,
    private val pathResolver: DrivePathResolver? = null,
) : ChangeFeedStorage {

    private fun ensureConfigured(): Pair<DriveHttpClient, DrivePathResolver> {
        val c = client ?: throw NotImplementedError("Google Drive adapter is not configured")
        val r = pathResolver ?: throw NotImplementedError("Google Drive adapter is not configured")
        return c to r
    }

    override suspend fun getLatestChangeToken(): String {
        val (client, _) = ensureConfigured()
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
        val (client, pathResolver) = ensureConfigured()
        val changes = mutableListOf<RemoteChange>()
        var pageToken: String? = sinceToken
        var newStartToken: String? = null

        do {
            val response = client.executeWithRetry { token ->
                client.httpClient.get("https://www.googleapis.com/drive/v3/changes") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    parameter("pageToken", pageToken)
                    parameter("includeRemoved", "true")
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
                val isDeleted = change.removed || file?.trashed == true
                if (isDeleted) {
                    val path = pathResolver.resolvePathById(change.fileId)
                        ?: pathResolver.invalidateById(change.fileId)
                        ?: RemotePath(change.fileId)
                    changes.add(RemoteChange(path, isDeleted = true, entry = null))
                    pathResolver.invalidate(path)
                } else if (file != null) {
                    val path = pathResolver.resolveRemotePathForFile(file) ?: RemotePath(file.name)
                    val entry = toRemoteEntry(file, path)
                    changes.add(RemoteChange(path, isDeleted = false, entry = entry))
                    pathResolver.registerPath(path, file)
                }
            }

            pageToken = result.nextPageToken
        } while (pageToken != null)

        return RemoteChangesResult(changes, newStartToken ?: sinceToken)
    }

    override suspend fun list(dir: RemotePath): List<RemoteEntry> {
        val (client, pathResolver) = ensureConfigured()
        val folderId = if (dir.value.isEmpty()) {
            pathResolver.resolveRootFolderId()
        } else {
            val folderFile = pathResolver.resolvePath(dir) ?: return emptyList()
            if (!folderFile.isDirectory) return emptyList()
            folderFile.id
        }

        val result = mutableListOf<RemoteEntry>()
        var pageToken: String? = null

        do {
            val response = client.executeWithRetry { token ->
                client.httpClient.get("https://www.googleapis.com/drive/v3/files") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    parameter("q", "'$folderId' in parents and trashed = false")
                    parameter("fields", "nextPageToken,files(id,name,mimeType,size,modifiedTime,md5Checksum,parents,trashed)")
                    parameter("pageSize", 100)
                    if (pageToken != null) {
                        parameter("pageToken", pageToken)
                    }
                }
            }

            if (!response.status.isSuccess()) {
                throw DriveHttpException(response.status, "Failed to list folder $folderId")
            }

            val list = response.body<GoogleDriveFileList>()
            val deduplicated = list.files
                .filter { !it.trashed }
                .groupBy { it.name }
                .mapValues { (_, candidates) ->
                    candidates.maxByOrNull { it.modifiedTime ?: "" } ?: candidates.first()
                }.values

            for (file in deduplicated) {
                val childPath = if (dir.value.isEmpty()) {
                    RemotePath(file.name)
                } else {
                    RemotePath("${dir.value}/${file.name}")
                }
                pathResolver.registerPath(childPath, file)
                result.add(toRemoteEntry(file, childPath))
            }

            pageToken = list.nextPageToken
        } while (pageToken != null)

        return result
    }

    /**
     * Весь видимый приложению набор файлов (`drive.file`) одним постраничным запросом без фильтра по родителю,
     * затем дерево строится в памяти от папки [root]. Всё, что не происходит от неё (чужие папки, сироты,
     * циклы), отбрасывается. Дубликаты имён в одной папке — как в [list]: остаётся самый свежий по `modifiedTime`.
     * Прогресс: после каждой страницы — всего увиденных (не удалённых) папок и файлов, ещё без проверки
     * происхождения; в конце — точные числа для поддерева (папки включая сам [root]).
     */
    override suspend fun listAll(root: RemotePath, onProgress: (folders: Int, files: Int) -> Unit): List<RemoteEntry> {
        val (client, pathResolver) = ensureConfigured()
        val rootId = if (root.value.isEmpty()) {
            pathResolver.resolveRootFolderId()
        } else {
            val folder = pathResolver.resolvePath(root) ?: return emptyList()
            if (!folder.isDirectory) return emptyList()
            folder.id
        }
        // Кэши заполняются заново из этого обхода (registerPath ниже). Пропавшее из списка
        // больше не отдаётся из кэша: stat пойдёт живым запросом. Чистим всё, не только поддерево, —
        // это проще и всегда корректно (лишь дороже для путей вне [root]).
        pathResolver.resetPathCaches()

        // parentId -> (name -> выбранный кандидат); порядок первых появлений сохраняется
        val children = HashMap<String, LinkedHashMap<String, GoogleDriveFile>>()
        var foldersSeen = 0
        var filesSeen = 0
        var pageToken: String? = null

        do {
            val response = client.executeWithRetry { token ->
                client.httpClient.get("https://www.googleapis.com/drive/v3/files") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    parameter("q", "trashed = false")
                    parameter("fields", "nextPageToken,files(id,name,mimeType,size,modifiedTime,md5Checksum,parents,trashed)")
                    parameter("pageSize", 1000)
                    if (pageToken != null) {
                        parameter("pageToken", pageToken)
                    }
                }
            }

            if (!response.status.isSuccess()) {
                throw DriveHttpException(response.status, "Failed to list Drive files")
            }

            val page = response.body<GoogleDriveFileList>()
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
        var folders = 1
        val ancestors = mutableSetOf(rootId)

        suspend fun walk(folderId: String, dir: RemotePath) {
            val kids = children[folderId] ?: return
            for (file in kids.values) {
                if (file.id in ancestors) continue // цикл
                val childPath = if (dir.value.isEmpty()) RemotePath(file.name) else RemotePath("${dir.value}/${file.name}")
                pathResolver.registerPath(childPath, file)
                if (file.isDirectory) {
                    folders++
                    ancestors += file.id
                    walk(file.id, childPath)
                    ancestors -= file.id
                } else {
                    result.add(toRemoteEntry(file, childPath))
                }
            }
        }
        walk(rootId, root)
        onProgress(folders, result.size)
        return result
    }

    override suspend fun stat(path: RemotePath): RemoteEntry? {
        val (_, pathResolver) = ensureConfigured()
        val file = pathResolver.resolvePath(path) ?: return null
        return toRemoteEntry(file, path)
    }

    override suspend fun read(path: RemotePath): Source {
        val (client, pathResolver) = ensureConfigured()
        val file = pathResolver.resolvePath(path)
            ?: throw NoSuchElementException("No such file in Drive: ${path.value}")

        val response = client.executeWithRetry { token ->
            client.httpClient.get("https://www.googleapis.com/drive/v3/files/${file.id}") {
                header(HttpHeaders.Authorization, "Bearer $token")
                parameter("alt", "media")
            }
        }

        if (!response.status.isSuccess()) {
            throw DriveHttpException(response.status, "Failed to read file ${file.id}")
        }

        val bytes = response.body<ByteArray>()
        return Buffer().also { it.write(bytes) }
    }

    override suspend fun write(path: RemotePath, data: Source): RemoteEntry {
        val (client, pathResolver) = ensureConfigured()
        val uploader = dev.mimir.drive.upload.DriveUploader(client, pathResolver)
        return uploader.upload(path, data)
    }

    /** Файл переносится в корзину Drive, а не стирается: ошибочное удаление можно отменить в течение 30 дней. */
    override suspend fun delete(path: RemotePath) {
        val (client, pathResolver) = ensureConfigured()
        val file = pathResolver.resolvePath(path) ?: return
        val response = client.executeWithRetry { token ->
            client.httpClient.patch("https://www.googleapis.com/drive/v3/files/${file.id}") {
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

    private fun toRemoteEntry(file: GoogleDriveFile, path: RemotePath): RemoteEntry {
        val instant = file.modifiedTime?.let {
            try {
                Instant.parse(it)
            } catch (_: Exception) {
                Clock.System.now()
            }
        } ?: Clock.System.now()

        return RemoteEntry(
            path = path,
            size = file.size ?: 0L,
            modifiedAt = instant,
            revision = file.md5Checksum ?: file.id,
            isDirectory = file.isDirectory,
        )
    }

    @Serializable
    private data class TrashRequest(val trashed: Boolean)
}
