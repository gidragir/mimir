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

interface RemoteStorage {
    suspend fun list(dir: RemotePath): List<RemoteEntry>
    suspend fun stat(path: RemotePath): RemoteEntry?
    suspend fun read(path: RemotePath): Source
    suspend fun write(path: RemotePath, data: Source): RemoteEntry
    suspend fun delete(path: RemotePath)
}
