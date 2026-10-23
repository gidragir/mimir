package dev.mimir.core

data class Conflict(val id: VaultFileId, val local: ByteArray, val remote: ByteArray)

sealed interface Resolution {
    data object KeepLocal : Resolution
    data object KeepRemote : Resolution
    data class Merged(val content: ByteArray) : Resolution
}

interface ConflictResolver {
    suspend fun resolve(conflict: Conflict): Resolution
}
