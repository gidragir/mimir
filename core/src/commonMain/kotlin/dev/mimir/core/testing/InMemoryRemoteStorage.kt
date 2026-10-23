package dev.mimir.core

import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlin.time.Clock
import kotlin.time.Instant

/** Хранилище в памяти: для тестов и как образец адаптера. */
class InMemoryRemoteStorage : ChangeFeedStorage {
    private class Stored(val bytes: ByteArray, val modifiedAt: Instant, val revision: String)
    private data class ChangeRecord(val version: Long, val change: RemoteChange)

    private val files = LinkedHashMap<String, Stored>()
    private var revisionCounter = 0
    private var changeVersion = 0L
    private val changeHistory = mutableListOf<ChangeRecord>()

    override suspend fun getLatestChangeToken(): String = changeVersion.toString()

    override suspend fun getChanges(sinceToken: String): RemoteChangesResult {
        val since = sinceToken.toLongOrNull() ?: 0L
        val filtered = changeHistory.filter { it.version > since }.map { it.change }
        return RemoteChangesResult(filtered, changeVersion.toString())
    }

    override suspend fun list(dir: RemotePath): List<RemoteEntry> {
        val prefix = dir.segments
        val result = LinkedHashMap<String, RemoteEntry>()
        for ((key, stored) in files) {
            val segments = RemotePath(key).segments
            if (segments.size <= prefix.size || segments.subList(0, prefix.size) != prefix) continue
            val childPath = RemotePath.of(segments.subList(0, prefix.size + 1))
            result.getOrPut(childPath.value) {
                if (segments.size == prefix.size + 1) entry(childPath, stored)
                else RemoteEntry(childPath, 0, stored.modifiedAt, null, isDirectory = true)
            }
        }
        return result.values.toList()
    }

    override suspend fun stat(path: RemotePath): RemoteEntry? {
        files[path.value]?.let { return entry(path, it) }
        return list(path).firstOrNull()?.let { RemoteEntry(path, 0, it.modifiedAt, null, isDirectory = true) }
    }

    override suspend fun read(path: RemotePath): Source {
        val stored = files[path.value] ?: throw NoSuchElementException("No such file: ${path.value}")
        return Buffer().also { it.write(stored.bytes) }
    }

    override suspend fun write(path: RemotePath, data: Source): RemoteEntry {
        val stored = Stored(data.readByteArray(), Clock.System.now(), "r${++revisionCounter}")
        files[path.value] = stored
        val resultEntry = entry(path, stored)
        changeHistory.add(ChangeRecord(++changeVersion, RemoteChange(path, isDeleted = false, entry = resultEntry)))
        return resultEntry
    }

    override suspend fun delete(path: RemotePath) {
        if (files.remove(path.value) != null) {
            changeHistory.add(ChangeRecord(++changeVersion, RemoteChange(path, isDeleted = true, entry = null)))
        }
    }

    private fun entry(path: RemotePath, stored: Stored) =
        RemoteEntry(path, stored.bytes.size.toLong(), stored.modifiedAt, stored.revision, isDirectory = false)
}
