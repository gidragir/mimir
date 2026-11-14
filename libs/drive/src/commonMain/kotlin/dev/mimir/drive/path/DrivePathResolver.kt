package dev.mimir.drive.path

import dev.mimir.core.RemotePath
import dev.mimir.drive.api.GoogleDriveFile
import dev.mimir.drive.api.GoogleDriveFileList
import dev.mimir.drive.client.DriveHttpClient
import dev.mimir.drive.client.DriveHttpException
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

class DrivePathResolver(
    private val client: DriveHttpClient,
    private val rootFolderName: String = "Mimir",
) {
    private val cache = DrivePathCache()
    private val rootResolutionMutex = Mutex()

    /**
     * Сериализует «найти → создать» папок: без неё параллельные загрузки в новую папку оба не находят её
     * и создают дубликаты с одним именем.
     */
    private val folderCreationMutex = Mutex()

    suspend fun resolveRootFolderId(): String {
        cache.rootId()?.let { return it }
        return rootResolutionMutex.withLock {
            cache.rootId()?.let { return it }
            val id = findOrCreateFolder(parentFolderId = "root", folderName = rootFolderName)
            cache.setRoot(id)
            id
        }
    }

    suspend fun registerPath(path: RemotePath, file: GoogleDriveFile) {
        cache.put(path, file)
    }

    suspend fun registerPaths(items: List<Pair<RemotePath, GoogleDriveFile>>) {
        cache.putAll(items)
    }

    suspend fun resolvePathById(fileId: String): RemotePath? = cache.pathOf(fileId)

    suspend fun invalidateById(fileId: String): RemotePath? = cache.removeById(fileId)

    suspend fun resolveRemotePathForFile(file: GoogleDriveFile): RemotePath? {
        val rootId = resolveRootFolderId()
        return resolveNodePath(file, rootId, visited = mutableSetOf())
    }

    private suspend fun resolveNodePath(
        file: GoogleDriveFile,
        rootId: String,
        visited: MutableSet<String>,
    ): RemotePath? {
        if (file.id == rootId) return RemotePath("")
        cache.pathOf(file.id)?.let { return it }
        if (!visited.add(file.id) || file.parents.isEmpty()) return null

        val parentId = file.parents.first()
        val parentPath = if (parentId == rootId) {
            RemotePath("")
        } else {
            cache.pathOf(parentId) ?: run {
                val parent = fetchFileById(parentId) ?: return null
                if (!parent.isDirectory) return null
                resolveNodePath(parent, rootId, visited) ?: return null
            }
        }

        val fullPath = if (parentPath.value.isEmpty()) RemotePath(file.name) else RemotePath("${parentPath.value}/${file.name}")
        cache.put(fullPath, file)
        return fullPath
    }

    suspend fun fetchFileById(fileId: String): GoogleDriveFile? {
        val response = client.executeWithRetry { token ->
            client.httpClient.get("https://www.googleapis.com/drive/v3/files/$fileId") {
                header(HttpHeaders.Authorization, "Bearer $token")
                parameter("fields", "id,name,mimeType,size,modifiedTime,md5Checksum,parents,trashed")
                parameter("supportsAllDrives", "false")
            }
        }
        if (!response.status.isSuccess()) {
            return null
        }
        return response.body<GoogleDriveFile>()
    }

    suspend fun resolvePath(path: RemotePath): GoogleDriveFile? {
        if (path.value.isEmpty()) {
            val rootId = resolveRootFolderId()
            return GoogleDriveFile(
                id = rootId,
                name = rootFolderName,
                mimeType = FOLDER_MIME_TYPE,
                parents = listOf("root"),
                trashed = false,
            )
        }

        cache.fileAt(path)?.let { return it }

        val segments = path.segments
        val parentFolderId = resolveOrCreateFolderInternal(RemotePath.of(segments.dropLast(1)), createIfMissing = false)
            ?: return null

        val file = queryFileInFolder(parentFolderId, segments.last()) ?: return null
        cache.put(path, file)
        return file
    }

    suspend fun resolveOrCreateFolderPath(dir: RemotePath): String =
        resolveOrCreateFolderInternal(dir, createIfMissing = true)
            ?: throw IllegalStateException("Failed to resolve or create folder: ${dir.value}")

    private suspend fun resolveOrCreateFolderInternal(dir: RemotePath, createIfMissing: Boolean): String? {
        if (dir.value.isEmpty()) {
            return resolveRootFolderId()
        }
        if (!createIfMissing) return walkFolders(dir, createIfMissing = false)
        return folderCreationMutex.withLock { walkFolders(dir, createIfMissing = true) }
    }

    private suspend fun walkFolders(dir: RemotePath, createIfMissing: Boolean): String? {
        var currentParentId = resolveRootFolderId()
        val currentSegments = mutableListOf<String>()

        for (segment in dir.segments) {
            currentSegments.add(segment)
            val subPath = currentSegments.joinToString("/")
            val cachedId = cache.folderIdAt(subPath)

            if (cachedId != null) {
                currentParentId = cachedId
            } else {
                val found = queryFolderInFolder(currentParentId, segment)
                val folderId = when {
                    found != null -> found.id
                    createIfMissing -> createFolder(currentParentId, segment)
                    else -> return null
                }
                cache.putFolder(RemotePath.of(currentSegments), folderId)
                currentParentId = folderId
            }
        }

        return currentParentId
    }

    suspend fun invalidate(path: RemotePath) {
        cache.removeTree(path)
    }

    /**
     * Сбрасывает кэши путей (файлы, папки, id→путь), сохраняя найденную папку хранилища.
     * Вызывается перед пакетным обходом: всё, что в нём не встретится, дальше ищется живым запросом,
     * поэтому удалённый в Drive файл не «воскресает» из кэша прошлого прогона.
     */
    suspend fun resetPathCaches() {
        cache.resetPaths()
    }

    suspend fun clearCache() {
        cache.clearAll()
    }

    private suspend fun queryFileInFolder(parentFolderId: String, fileName: String): GoogleDriveFile? {
        val query = "'$parentFolderId' in parents and name = '${escapeQuery(fileName)}' and trashed = false"
        return listFilesByQuery(query).maxByOrNull { it.modifiedTime ?: "" }
    }

    private suspend fun queryFolderInFolder(parentFolderId: String, folderName: String): GoogleDriveFile? {
        val query = "'$parentFolderId' in parents and name = '${escapeQuery(folderName)}' and mimeType = '$FOLDER_MIME_TYPE' and trashed = false"
        return listFilesByQuery(query).maxByOrNull { it.modifiedTime ?: "" }
    }

    private suspend fun findOrCreateFolder(parentFolderId: String, folderName: String): String =
        queryFolderInFolder(parentFolderId, folderName)?.id ?: createFolder(parentFolderId, folderName)

    private suspend fun createFolder(parentFolderId: String, folderName: String): String {
        val body = FolderCreateRequest(
            name = folderName,
            mimeType = FOLDER_MIME_TYPE,
            parents = listOf(parentFolderId),
        )
        val response = client.executeWithRetry { token ->
            client.httpClient.post("https://www.googleapis.com/drive/v3/files") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        if (!response.status.isSuccess()) {
            throw DriveHttpException(response.status, "Failed to create folder $folderName")
        }
        return response.body<GoogleDriveFile>().id
    }

    private suspend fun listFilesByQuery(query: String): List<GoogleDriveFile> {
        val response = client.executeWithRetry { token ->
            client.httpClient.get("https://www.googleapis.com/drive/v3/files") {
                header(HttpHeaders.Authorization, "Bearer $token")
                parameter("q", query)
                parameter("fields", "files(id,name,mimeType,size,modifiedTime,md5Checksum,parents,trashed)")
                parameter("pageSize", 100)
            }
        }
        if (!response.status.isSuccess()) {
            throw DriveHttpException(response.status, "Failed to list files with query: $query")
        }
        return response.body<GoogleDriveFileList>().files
    }

    private fun escapeQuery(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'")

    @Serializable
    private data class FolderCreateRequest(
        val name: String,
        val mimeType: String,
        val parents: List<String>,
    )

    companion object {
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
    }
}
