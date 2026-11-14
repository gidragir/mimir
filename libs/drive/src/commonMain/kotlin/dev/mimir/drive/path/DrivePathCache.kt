package dev.mimir.drive.path

import dev.mimir.core.RemotePath
import dev.mimir.drive.api.GoogleDriveFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class DrivePathCache {
    private val mutex = Mutex()
    private var rootFolderId: String? = null
    private val folderCache = mutableMapOf<String, String>()
    private val fileCache = mutableMapOf<String, GoogleDriveFile>()
    private val idToPath = mutableMapOf<String, RemotePath>()

    suspend fun rootId(): String? = mutex.withLock { rootFolderId }

    suspend fun setRoot(id: String) {
        mutex.withLock {
            rootFolderId = id
            folderCache[""] = id
            idToPath[id] = RemotePath("")
        }
    }

    suspend fun fileAt(path: RemotePath): GoogleDriveFile? = mutex.withLock { fileCache[path.value] }

    suspend fun folderIdAt(subPath: String): String? = mutex.withLock { folderCache[subPath] }

    suspend fun pathOf(fileId: String): RemotePath? = mutex.withLock { idToPath[fileId] }

    suspend fun put(path: RemotePath, file: GoogleDriveFile) {
        mutex.withLock { putLocked(path, file) }
    }

    suspend fun putAll(items: List<Pair<RemotePath, GoogleDriveFile>>) {
        if (items.isEmpty()) return
        mutex.withLock {
            for ((path, file) in items) {
                putLocked(path, file)
            }
        }
    }

    suspend fun putFolder(path: RemotePath, folderId: String) {
        mutex.withLock {
            folderCache[path.value] = folderId
            idToPath[folderId] = path
        }
    }

    suspend fun removeById(fileId: String): RemotePath? = mutex.withLock {
        val path = idToPath.remove(fileId) ?: return@withLock null
        fileCache.remove(path.value)
        folderCache.remove(path.value)
        path
    }

    suspend fun removeTree(path: RemotePath) {
        mutex.withLock {
            val key = path.value
            fileCache.remove(key)?.let { idToPath.remove(it.id) }
            folderCache.remove(key)?.let { idToPath.remove(it) }
            val prefix = if (key.isEmpty()) "" else "$key/"
            val fileKeys = fileCache.filterKeys { it.startsWith(prefix) }
            for ((_, f) in fileKeys) idToPath.remove(f.id)
            fileCache.keys.removeAll(fileKeys.keys)
            val folderKeys = folderCache.filterKeys { it.startsWith(prefix) }
            for ((_, id) in folderKeys) idToPath.remove(id)
            folderCache.keys.removeAll(folderKeys.keys)
        }
    }

    suspend fun resetPaths() {
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

    suspend fun clearAll() {
        mutex.withLock {
            rootFolderId = null
            folderCache.clear()
            fileCache.clear()
            idToPath.clear()
        }
    }

    private fun putLocked(path: RemotePath, file: GoogleDriveFile) {
        idToPath[file.id] = path
        fileCache[path.value] = file
        if (file.isDirectory) {
            folderCache[path.value] = file.id
        }
    }
}
