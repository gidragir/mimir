package dev.mimir.drive

import dev.mimir.core.ChangeFeedStorage
import dev.mimir.core.ChangePeek
import dev.mimir.core.RemoteAnomaly
import dev.mimir.core.RemoteAnomalyKind
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
import dev.mimir.drive.client.DriveOp
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val anomaliesMutex = Mutex()
    private val pendingAnomalies = mutableListOf<RemoteAnomaly>()

    override suspend fun drainAnomalies(): List<RemoteAnomaly> = anomaliesMutex.withLock {
        if (pendingAnomalies.isEmpty()) return@withLock emptyList()
        val drained = pendingAnomalies.toList()
        pendingAnomalies.clear()
        drained
    }

    override suspend fun getLatestChangeToken(): String {
        val response = client.executeWithRetry(DriveOp.StartToken) { token ->
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

    override suspend fun peekChanges(sinceToken: String): ChangePeek {
        var hasChanges = false
        var pageToken: String? = sinceToken
        var newStartToken: String? = null

        do {
            val response = client.executeWithRetry(DriveOp.ChangesPage) { token ->
                client.httpClient.get("https://www.googleapis.com/drive/v3/changes") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    parameter("pageToken", pageToken)
                    parameter("includeRemoved", "true")
                    parameter("supportsAllDrives", "false")
                    parameter("pageSize", 1000)
                    parameter("fields", "newStartPageToken,nextPageToken,changes(fileId,removed,file(md5Checksum,trashed,mimeType))")
                }
            }
            if (!response.status.isSuccess()) {
                throw DriveHttpException(response.status, "Failed to peek changes for token: $pageToken")
            }

            val result = response.body<GoogleDriveChangeList>()
            newStartToken = result.newStartPageToken
            if (!hasChanges) {
                for (change in result.changes) {
                    val file = change.file
                    val isEcho = !change.removed &&
                        file != null &&
                        !file.trashed &&
                        pathResolver.isOwnEcho(change.fileId, file.md5Checksum)
                    if (!isEcho) {
                        hasChanges = true
                        break
                    }
                }
            }
            pageToken = result.nextPageToken
        } while (pageToken != null)

        return ChangePeek(hasChanges = hasChanges, nextToken = newStartToken ?: sinceToken)
    }

    override suspend fun getChanges(sinceToken: String): RemoteChangesResult {
        val changes = mutableListOf<RemoteChange>()
        var pageToken: String? = sinceToken
        var newStartToken: String? = null
        var unresolved = false

        do {
            val response = client.executeWithRetry(DriveOp.ChangesPage) { token ->
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
                    if (path != null) {
                        changes.add(RemoteChange(path, isDeleted = true, entry = null))
                        pathResolver.invalidate(path)
                        pathResolver.invalidateById(change.fileId)
                    } else {
                        unresolved = true
                    }
                } else if (file != null) {
                    if (file.isDirectory) {
                        unresolved = true
                    } else {
                        val newPath = pathResolver.resolvePathFromMetadata(file) ?: continue
                        val oldPath = pathResolver.resolvePathById(file.id)
                        if (oldPath != null && oldPath != newPath) {
                            changes.add(RemoteChange(oldPath, isDeleted = true, entry = null))
                            pathResolver.invalidate(oldPath)
                        }
                        changes.add(RemoteChange(newPath, isDeleted = false, entry = file.toRemoteEntry(newPath)))
                        pathResolver.registerPath(newPath, file)
                    }
                }
            }
            pageToken = result.nextPageToken
        } while (pageToken != null)

        return RemoteChangesResult(changes, newStartToken ?: sinceToken, unresolved = unresolved)
    }

    override suspend fun list(dir: RemotePath): List<RemoteEntry> {
        val folderId = resolveFolderId(dir) ?: return emptyList()
        val folderIds = pathResolver.aliasesOf(folderId)
        val parentExpr = folderIds.joinToString(" or ") { "'$it' in parents" }
        val query = if (folderIds.size > 1) "($parentExpr) and trashed = false" else "$parentExpr and trashed = false"

        val rawKids = mutableListOf<GoogleDriveFile>()
        var pageToken: String? = null
        do {
            val page = fetchFilesPage(query, 100, pageToken, "Failed to list folder $folderId", DriveOp.ListQuery)
            for (file in page.files) {
                if (!file.trashed) rawKids += file
            }
            pageToken = page.nextPageToken
        } while (pageToken != null)

        val result = mutableListOf<RemoteEntry>()
        val registered = mutableListOf<Pair<RemotePath, GoogleDriveFile>>()
        val anomalies = mutableListOf<RemoteAnomaly>()
        val aliasesToRegister = mutableListOf<Pair<String, List<String>>>()

        processDirectoryChildren(
            dir = dir,
            rawChildren = rawKids,
            anomalies = anomalies,
            onFolderGroup = { childPath, canonical, group ->
                registered += childPath to canonical
                result += canonical.toRemoteEntry(childPath)
                if (group.size > 1) aliasesToRegister += canonical.id to group.map { it.id }
            },
            onFileGroup = { childPath, canonical ->
                registered += childPath to canonical
                result += canonical.toRemoteEntry(childPath)
            },
        )

        for ((canonicalId, ids) in aliasesToRegister) {
            pathResolver.registerAliases(canonicalId, ids)
        }
        pathResolver.registerPaths(registered)
        if (anomalies.isNotEmpty()) {
            anomaliesMutex.withLock { pendingAnomalies += anomalies }
        }
        return result
    }

    /**
     * Весь видимый приложению набор файлов (`drive.file`) одним постраничным запросом без фильтра по родителю,
     * затем дерево строится в памяти от папки [root]. Всё, что не происходит от неё (чужие папки, сироты,
     * циклы), отбрасывается. Одноимённые папки объединяются под канонической (min `createdTime`, затем min `id`),
     * одноимённые файлы — по max `modifiedTime`, затем min `id`; дубликаты фиксируются в [drainAnomalies].
     */
    override suspend fun listAll(root: RemotePath, onProgress: (folders: Int, files: Int) -> Unit): List<RemoteEntry> {
        val rootId = resolveFolderId(root) ?: return emptyList()
        // Кэши заполняются заново из этого обхода (registerPaths ниже). Пропавшее из списка
        // больше не отдаётся из кэша: stat пойдёт живым запросом.
        pathResolver.resetPathCaches()

        val childrenByParent = HashMap<String, MutableList<GoogleDriveFile>>()
        var foldersSeen = 0
        var filesSeen = 0
        var pageToken: String? = null

        do {
            val page = fetchFilesPage("trashed = false", 1000, pageToken, "Failed to list Drive files", DriveOp.ListAllPage)
            for (file in page.files) {
                if (file.trashed) continue
                if (file.isDirectory) foldersSeen++ else filesSeen++
                for (parent in file.parents) {
                    childrenByParent.getOrPut(parent) { mutableListOf() }.add(file)
                }
            }
            onProgress(foldersSeen, filesSeen)
            pageToken = page.nextPageToken
        } while (pageToken != null)

        val result = mutableListOf<RemoteEntry>()
        val registered = mutableListOf<Pair<RemotePath, GoogleDriveFile>>()
        val anomalies = mutableListOf<RemoteAnomaly>()
        val aliasesToRegister = mutableListOf<Pair<String, List<String>>>()
        val rootIds = pathResolver.aliasesOf(rootId)
        val visited = HashSet<String>(rootIds)
        val queue = ArrayDeque<Pair<List<String>, RemotePath>>()
        queue.addLast(rootIds to root)
        var folders = 1

        while (queue.isNotEmpty()) {
            val (folderIds, dir) = queue.removeFirst()
            val rawKids = folderIds.flatMap { childrenByParent[it].orEmpty() }
            if (rawKids.isEmpty()) continue

            processDirectoryChildren(
                dir = dir,
                rawChildren = rawKids,
                anomalies = anomalies,
                onFolderGroup = { childPath, canonical, group ->
                    if (group.size > 1) aliasesToRegister += canonical.id to group.map { it.id }
                    val unvisitedIds = group.map { it.id }.filter { visited.add(it) }
                    if (unvisitedIds.isNotEmpty()) {
                        folders++
                        registered += childPath to canonical
                        queue.addLast(unvisitedIds to childPath)
                    }
                },
                onFileGroup = { childPath, canonical ->
                    registered += childPath to canonical
                    result += canonical.toRemoteEntry(childPath)
                },
            )
        }

        for ((canonicalId, ids) in aliasesToRegister) {
            pathResolver.registerAliases(canonicalId, ids)
        }
        pathResolver.registerPaths(registered)
        anomaliesMutex.withLock {
            pendingAnomalies.clear()
            pendingAnomalies += anomalies
        }
        onProgress(folders, result.size)
        return result
    }

    private inline fun processDirectoryChildren(
        dir: RemotePath,
        rawChildren: List<GoogleDriveFile>,
        anomalies: MutableList<RemoteAnomaly>,
        onFolderGroup: (childPath: RemotePath, canonical: GoogleDriveFile, group: List<GoogleDriveFile>) -> Unit,
        onFileGroup: (childPath: RemotePath, canonical: GoogleDriveFile) -> Unit,
    ) {
        val unique = rawChildren.distinctBy { it.id }
        val (folderItems, fileItems) = unique.partition { it.isDirectory }

        for ((name, group) in folderItems.groupBy { it.name }) {
            val childPath = childPathOf(dir, name)
            val canonical = group.minWith(FOLDER_CANONICAL_ORDER)
            if (group.size > 1) {
                anomalies += RemoteAnomaly(childPath, RemoteAnomalyKind.DuplicateFolder, group.size)
            }
            onFolderGroup(childPath, canonical, group)
        }

        for ((name, group) in fileItems.groupBy { it.name }) {
            val childPath = childPathOf(dir, name)
            val canonical = group.minWith(FILE_CANONICAL_ORDER)
            if (group.size > 1) {
                anomalies += RemoteAnomaly(childPath, RemoteAnomalyKind.DuplicateFile, group.size)
            }
            onFileGroup(childPath, canonical)
        }
    }

    private fun childPathOf(dir: RemotePath, name: String): RemotePath =
        if (dir.value.isEmpty()) RemotePath(name) else RemotePath("${dir.value}/$name")

    override suspend fun stat(path: RemotePath): RemoteEntry? =
        pathResolver.resolvePath(path)?.toRemoteEntry(path)

    override suspend fun read(path: RemotePath): Source = downloader.read(path)

    override suspend fun write(path: RemotePath, data: Source): RemoteEntry = uploader.upload(path, data)

    /** Файл переносится в корзину Drive, а не стирается: ошибочное удаление можно отменить в течение 30 дней. */
    override suspend fun delete(path: RemotePath) {
        val file = pathResolver.resolvePath(path) ?: return
        val response = client.executeWithRetry(DriveOp.Trash) { token ->
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
        op: DriveOp,
    ): GoogleDriveFileList {
        val response = client.executeWithRetry(op) { token ->
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
        const val FILE_LIST_FIELDS = "nextPageToken,files(id,name,mimeType,size,modifiedTime,createdTime,md5Checksum,parents,trashed)"
        val FOLDER_CANONICAL_ORDER = compareBy<GoogleDriveFile> { it.createdTime ?: "\uFFFF" }.thenBy { it.id }
        val FILE_CANONICAL_ORDER = compareByDescending<GoogleDriveFile> { it.modifiedTime ?: "" }.thenBy { it.id }
    }
}
