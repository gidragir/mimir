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

    suspend fun resolveRootFolderId(): String = mutex.withLock {
        rootFolderId?.let { return it }
        val id = findOrCreateFolder(parentFolderId = "root", folderName = rootFolderName)
        rootFolderId = id
        folderCache[""] = id
        id
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
                mutex.withLock { folderCache[subPath] = folderId }
                currentParentId = folderId
            }
        }

        return currentParentId
    }

    suspend fun invalidate(path: RemotePath) {
        mutex.withLock {
            val key = path.value
            fileCache.remove(key)
            folderCache.remove(key)
            // Инвалидируем все дочерние ключи
            val prefix = if (key.isEmpty()) "" else "$key/"
            fileCache.keys.removeAll { it.startsWith(prefix) }
            folderCache.keys.removeAll { it.startsWith(prefix) }
        }
    }

    suspend fun clearCache() {
        mutex.withLock {
            rootFolderId = null
            folderCache.clearexhaustive()
            fileCache.clear()
        }
    }

    private fun <K, V> MutableMap<K, V>.clearexhaustive() {
        clear()
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
