package dev.mimir.core.sync

import dev.mimir.core.RemotePath
import dev.mimir.core.VaultFileId
import kotlin.time.Instant

data class FileSyncRecord(
    val relativePath: String,
    val localId: VaultFileId,
    val baseHash: String,
    val localSize: Long,
    val localModifiedAt: Instant,
    val remotePath: RemotePath,
    val remoteRevision: String?,
    val remoteModifiedAt: Instant,
    val remoteSize: Long,
)

data class SyncIndex(
    val version: Int = 1,
    val records: Map<String, FileSyncRecord> = emptyMap(),
) {
    fun find(path: String): FileSyncRecord? = records[path]

    fun withRecord(record: FileSyncRecord): SyncIndex =
        copy(records = records + (record.relativePath to record))

    fun withoutRecord(path: String): SyncIndex =
        copy(records = records - path)
}
