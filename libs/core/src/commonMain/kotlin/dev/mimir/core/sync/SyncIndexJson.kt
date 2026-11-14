package dev.mimir.core.sync

import dev.mimir.core.RemotePath
import dev.mimir.core.VaultFileId
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * Единый формат сериализации [SyncIndex] в JSON, общий для десктопного демона, Android и плагина Obsidian.
 */
object SyncIndexJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(index: SyncIndex): String =
        json.encodeToString(IndexDto.serializer(), IndexDto.of(index))

    fun decode(text: String): SyncIndex =
        json.decodeFromString<IndexDto>(text).toIndex()

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    private class IndexDto(
        val version: Int,
        val records: List<RecordDto>,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val remoteChangeToken: String? = null,
    ) {
        fun toIndex() = SyncIndex(
            version = version,
            records = records.associate { it.relativePath to it.toRecord() },
            remoteChangeToken = remoteChangeToken,
        )

        companion object {
            fun of(index: SyncIndex) = IndexDto(
                version = index.version,
                records = index.records.values.map(RecordDto::of),
                remoteChangeToken = index.remoteChangeToken,
            )
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
