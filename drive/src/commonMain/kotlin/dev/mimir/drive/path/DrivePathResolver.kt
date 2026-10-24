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
    private val mutex = Mutex()
    private var rootFolderId: String? = null
    private val folderCache = mutableMapOf<String, String>()
    private val fileCache = mutableMapOf<String, GoogleDriveFile>()
    private val idToPath = mutableMapOf<String, RemotePath>()

    suspend fun resolveRootFolderId(): String = mutex.withLock {
        rootFolderId?.let { return it }
        val id = findOrCreateFolder(parentFolderId = "root", folderName = rootFolderName)
        rootFolderId = id
        folderCache[""] = id
        idToPath[id] = RemotePath("")
        id
    }

    suspend fun registerPath(path: RemotePath, file: GoogleDriveFile) {
        mutex.withLock {
            idToPath[file.id] = path
            fileCache[path.value] = file
            if (file.isDirectory) {
                folderCache[path.value] = file.id
            }
        }
    }

    suspend fun resolvePathById(fileId: String): RemotePath? = mutex.withLock {
        idToPath[fileId]
    }

    suspend fun invalidateById(fileId: String): RemotePath? = mutex.withLock {
        val path = idToPath.remove(fileId)
        if (path != null) {
            fileCache.remove(path.value)
            folderCache.remove(path.value)
        }
        path
    }

    suspend fun resolveRemotePathForFile(file: GoogleDriveFile): RemotePath? {
        val rootId = resolveRootFolderId()
        if (file.id == rootId) return RemotePath("")

        mutex.withLock {
            idToPath[file.id]?.let { return it }
        }

        if (file.parents.isEmpty()) return null
        val parentId = file.parents.first()

        val parentPath = if (parentId == rootId) {
            RemotePath("")
        } else {
            resolveFolderPathById(parentId, rootId) ?: return null
        }

        val fullPath = if (parentPath.value.isEmpty()) {
            RemotePath(file.name)
        } else {
            RemotePath("${parentPath.value}/${file.name}")
        }

        registerPath(fullPath, file)
        return fullPath
    }

    private suspend fun resolveFolderPathById(folderId: String, rootId: String): RemotePath? {
        mutex.withLock {
            idToPath[folderId]?.let { return it }
        }
        if (folderId == rootId) return RemotePath("")

        val parentFile = fetchFileById(folderId) ?: return null
        if (!parentFile.isDirectory || parentFile.parents.isEmpty()) return null

        val grandParentId = parentFile.parents.first()
        val grandParentPath = if (grandParentId == rootId) {
            RemotePath("")
        } else {
            resolveFolderPathById(grandParentId, rootId) ?: return null
        }

        val folderPath = if (grandParentPath.value.isEmpty()) {
            RemotePath(parentFile.name)
        } else {
            RemotePath("${grandParentPath.value}/${parentFile.name}")
        }

        registerPath(folderPath, parentFile)
        return folderPath
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

        val segments = path.segments
        val parentDirSegments = segments.dropLast(1)
        val fileName = segments.last()

        val parentFolderId = resolveOrCreateFolderInternal(RemotePath.of(parentDirSegments), createIfMissing = false)
            ?: return null

        val cacheKey = path.value
        mutex.withLock {
            fileCache[cacheKey]?.let { return it }
        }

        val file = queryFileInFolder(parentFolderId, fileName) ?: return null
        mutex.withLock {
            fileCache[cacheKey] = file
            idToPath[file.id] = path
            if (file.isDirectory) {
                folderCache[cacheKey] = file.id
            }
        }
        return file
    }

    suspend fun resolveOrCreateFolderPath(dir: RemotePath): String {
        return resolveOrCreateFolderInternal(dir, createIfMissing = true)
            ?: throw IllegalStateException("Failed to resolve or create folder: ${dir.value}")
    }

    private suspend fun resolveOrCreateFolderInternal(dir: RemotePath, createIfMissing: Boolean): String? {
        if (dir.value.isEmpty()) {
            return resolveRootFolderId()
        }

        var currentParentId = resolveRootFolderId()
        val currentSegments = mutableListOf<String>()

        for (segment in dir.segments) {
            currentSegments.add(segment)
            val subPath = currentSegments.joinToString("/")
            val cachedId = mutex.withLock { folderCache[subPath] }

            if (cachedId != null) {
                currentParentId = cachedId
            } else {
                val found = queryFolderInFolder(currentParentId, segment)
                val folderId = if (found != null) {
                    found.id
                } else if (createIfMissing) {
                    createFolder(currentParentId, segment)
                } else {
                    return null
                }
                val segPath = RemotePath.of(currentSegments)
                mutex.withLock {
                    folderCache[subPath] = folderId
                    idToPath[folderId] = segPath
                }
                currentParentId = folderId
            }
        }

        return currentParentId
    }

    suspend fun invalidate(path: RemotePath) {
        mutex.withLock {
            val key = path.value
            val file = fileCache.remove(key)
            if (file != null) {
                idToPath.remove(file.id)
            }
            val folderId = folderCache.remove(key)
            if (folderId != null) {
                idToPath.remove(folderId)
            }
            val prefix = if (key.isEmpty()) "" else "$key/"
            val fileKeysToRemove = fileCache.filterKeys { it.startsWith(prefix) }
            for ((_, f) in fileKeysToRemove) {
                idToPath.remove(f.id)
            }
            fileCache.keys.removeAll(fileKeysToRemove.keys)

            val folderKeysToRemove = folderCache.filterKeys { it.startsWith(prefix) }
            for ((_, id) in folderKeysToRemove) {
                idToPath.remove(id)
            }
            folderCache.keys.removeAll(folderKeysToRemove.keys)
        }
    }

    /**
     * Сбрасывает кэши путей (файлы, папки, id→путь), сохраняя найденную папку хранилища.
     * Вызывается перед пакетным обходом: всё, что в нём не встретится, дальше ищется живым запросом,
     * поэтому удалённый в Drive файл не «воскресает» из кэша прошлого прогона.
     */
    suspend fun resetPathCaches() {
        mutex.withLock {
            folderCache.clear()
            fileCache.clear()
            idToPath.clear()
            rootFolderId?.let { id ->
                folderCache[""] = id
                idToPath[id] = RemotePath("")
            }
        }
    }

    suspend fun clearCache() {
        mutex.withLock {
            rootFolderId = null
            folderCache.clear()
            fileCache.clear()
            idToPath.clear()
        }
    }

    private suspend fun queryFileInFolder(parentFolderId: String, fileName: String): GoogleDriveFile? {
        val query = "'$parentFolderId' in parents and name = '${escapeQuery(fileName)}' and trashed = false"
        val files = listFilesByQuery(query)
        return files.maxByOrNull { it.modifiedTime ?: "" }
    }

    private suspend fun queryFolderInFolder(parentFolderId: String, folderName: String): GoogleDriveFile? {
        val query = "'$parentFolderId' in parents and name = '${escapeQuery(folderName)}' and mimeType = '$FOLDER_MIME_TYPE' and trashed = false"
        val files = listFilesByQuery(query)
        return files.maxByOrNull { it.modifiedTime ?: "" }
    }

    private suspend fun findOrCreateFolder(parentFolderId: String, folderName: String): String {
        val existing = queryFolderInFolder(parentFolderId, folderName)
        return existing?.id ?: createFolder(parentFolderId, folderName)
    }

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
        val created = response.body<GoogleDriveFile>()
        return created.id
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
