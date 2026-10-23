package dev.mimir.core

import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlin.time.Clock
import kotlin.time.Instant

/** Хранилище в памяти: для тестов и как образец адаптера. */
class InMemoryRemoteStorage : RemoteStorage {
    private class Stored(val bytes: ByteArray, val modifiedAt: Instant, val revision: String)

    private val files = LinkedHashMap<String, Stored>()
    private var revisionCounter = 0

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
        return entry(path, stored)
    }

    override suspend fun delete(path: RemotePath) {
        files.remove(path.value)
    }

    private fun entry(path: RemotePath, stored: Stored) =
        RemoteEntry(path, stored.bytes.size.toLong(), stored.modifiedAt, stored.revision, isDirectory = false)
}
