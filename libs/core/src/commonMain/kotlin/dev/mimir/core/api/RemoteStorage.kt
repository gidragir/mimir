package dev.mimir.core

import kotlinx.io.Source
import kotlin.jvm.JvmInline
import kotlin.time.Instant

/** "/"-разделённый путь без ведущего "/"; пустая строка — корень. */
@JvmInline
value class RemotePath(val value: String) {
    val segments: List<String> get() = value.split('/').filter { it.isNotEmpty() }

    companion object {
        fun of(segments: List<String>) = RemotePath(segments.joinToString("/"))
    }
}

data class RemoteEntry(
    val path: RemotePath,
    val size: Long,
    val modifiedAt: Instant,
    val revision: String?,
    val isDirectory: Boolean,
)

data class StorageCapabilities(val changeFeed: Boolean)

data class RemoteChange(
    val path: RemotePath,
    val isDeleted: Boolean,
    val entry: RemoteEntry? = null,
)

data class RemoteChangesResult(
    val changes: List<RemoteChange>,
    val nextToken: String,
)

interface RemoteStorage {
    suspend fun list(dir: RemotePath): List<RemoteEntry>
    suspend fun stat(path: RemotePath): RemoteEntry?
    suspend fun read(path: RemotePath): Source
    suspend fun write(path: RemotePath, data: Source): RemoteEntry
    suspend fun delete(path: RemotePath)

    /**
     * Все файлы (без каталогов) поддерева [root]; пути — ровно такие, какие вернул бы [list].
     * [onProgress] получает (каталогов просмотрено, файлов найдено) по мере обхода.
     * По умолчанию — рекурсия через [list]; адаптеры могут переопределить пакетным запросом.
     */
    suspend fun listAll(
        root: RemotePath = RemotePath(""),
        onProgress: (folders: Int, files: Int) -> Unit = { _, _ -> },
    ): List<RemoteEntry> {
        val files = mutableListOf<RemoteEntry>()
        var folders = 0
        suspend fun walk(dir: RemotePath) {
            val entries = list(dir)
            folders++
            onProgress(folders, files.size)
            for (entry in entries) {
                if (entry.isDirectory) {
                    walk(entry.path)
                } else {
                    files.add(entry)
                    onProgress(folders, files.size)
                }
            }
        }
        walk(root)
        return files
    }
}

interface ChangeFeedStorage : RemoteStorage {
    val isChangeFeedSupported: Boolean get() = true
    suspend fun getLatestChangeToken(): String
    suspend fun getChanges(sinceToken: String): RemoteChangesResult
}
