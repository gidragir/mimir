package dev.mimir.runtime

import dev.mimir.core.RemotePath
import dev.mimir.core.VaultFileId
import dev.mimir.core.sync.FileSyncRecord
import dev.mimir.core.sync.SyncIndex
import dev.mimir.core.sync.SyncStateStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Instant

class JsonSyncStateStore(
    val indexFile: Path,
    /** Где исполняется блокирующая запись и чтение индекса; JVM/Android передают `Dispatchers.IO`. */
    private val ioDispatcher: CoroutineDispatcher,
) : SyncStateStore {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    override suspend fun load(): SyncIndex = mutex.withLock {
        withContext(ioDispatcher) {
            if (!Files.isRegularFile(indexFile)) return@withContext SyncIndex()
            // Повреждённый индекс не сбрасываем молча: пустой индекс вызвал бы полную пересверку.
            json.decodeFromString<IndexDto>(Files.readString(indexFile)).toIndex()
        }
    }

    override suspend fun save(index: SyncIndex) {
        mutex.withLock { withContext(ioDispatcher) { writeFileAtomically(indexFile, json.encodeToString(IndexDto.of(index))) } }
    }

    @Serializable
    private class IndexDto(val version: Int, val records: List<RecordDto>) {
        fun toIndex() = SyncIndex(version, records.associate { it.relativePath to it.toRecord() })

        companion object {
            fun of(index: SyncIndex) = IndexDto(index.version, index.records.values.map(RecordDto::of))
        }
    }

    @Serializable
    private class RecordDto(
        val relativePath: String,
        val localId: String,
        val baseHash: String,
        val localSize: Long,
        val localModifiedAtSeconds: Long,
        val localModifiedAtNanos: Int,
        val remotePath: String,
        val remoteRevision: String?,
        val remoteModifiedAtSeconds: Long,
        val remoteModifiedAtNanos: Int,
        val remoteSize: Long,
    ) {
        fun toRecord() = FileSyncRecord(
            relativePath = relativePath,
            localId = VaultFileId(localId),
            baseHash = baseHash,
            localSize = localSize,
            localModifiedAt = Instant.fromEpochSeconds(localModifiedAtSeconds, localModifiedAtNanos),
            remotePath = RemotePath(remotePath),
            remoteRevision = remoteRevision,
            remoteModifiedAt = Instant.fromEpochSeconds(remoteModifiedAtSeconds, remoteModifiedAtNanos),
            remoteSize = remoteSize,
        )

        companion object {
            fun of(r: FileSyncRecord) = RecordDto(
                relativePath = r.relativePath,
                localId = r.localId.value,
                baseHash = r.baseHash,
                localSize = r.localSize,
                localModifiedAtSeconds = r.localModifiedAt.epochSeconds,
                localModifiedAtNanos = r.localModifiedAt.nanosecondsOfSecond,
                remotePath = r.remotePath.value,
                remoteRevision = r.remoteRevision,
                remoteModifiedAtSeconds = r.remoteModifiedAt.epochSeconds,
                remoteModifiedAtNanos = r.remoteModifiedAt.nanosecondsOfSecond,
                remoteSize = r.remoteSize,
            )
        }
    }
}
