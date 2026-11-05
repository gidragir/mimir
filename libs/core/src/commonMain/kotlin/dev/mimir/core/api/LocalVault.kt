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
    suspend fun getEntry(relativePath: String): LocalEntry?
    suspend fun read(id: VaultFileId): Source
    suspend fun write(id: VaultFileId, data: Source)
    suspend fun write(relativePath: String, data: Source): LocalEntry
    suspend fun delete(id: VaultFileId)
    suspend fun deleteByPath(relativePath: String)
    fun changes(): Flow<VaultChange>

    /**
     * Пути (файлы или папки), которые vault намеренно не показывает, например символьные ссылки.
     * Всё под ними синхронизация не трогает: их отсутствие в [list] не значит, что файлы удалены.
     */
    suspend fun hiddenPaths(): Set<String> = emptySet()
}
