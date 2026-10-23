package dev.mimir.core

import kotlinx.coroutines.flow.Flow
import kotlinx.io.Source
import kotlin.jvm.JvmInline
import kotlin.time.Instant

@JvmInline
value class VaultFileId(val value: String)

data class LocalEntry(
    val id: VaultFileId,
    val relativePath: String,
    val size: Long,
    val modifiedAt: Instant,
)

sealed interface VaultChange {
    val id: VaultFileId

    data class Created(override val id: VaultFileId, val entry: LocalEntry) : VaultChange
    data class Modified(override val id: VaultFileId, val entry: LocalEntry) : VaultChange
    data class Deleted(override val id: VaultFileId) : VaultChange
}

interface LocalVault {
    suspend fun list(): List<LocalEntry>
    suspend fun read(id: VaultFileId): Source
    suspend fun write(id: VaultFileId, data: Source)
    suspend fun delete(id: VaultFileId)
    fun changes(): Flow<VaultChange>
}
