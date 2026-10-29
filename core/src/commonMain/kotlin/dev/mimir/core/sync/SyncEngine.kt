package dev.mimir.core

import kotlinx.coroutines.flow.StateFlow

enum class SyncErrorKind {
    Other,
    SignInRequired,
    Aborted,
}

sealed interface SyncStatus {
    data object NotConfigured : SyncStatus
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data object Offline : SyncStatus
    data class Error(
        val message: String,
        val kind: SyncErrorKind = SyncErrorKind.Other,
    ) : SyncStatus
}

interface SyncEngine {
    val status: StateFlow<SyncStatus>
    val progress: StateFlow<SyncProgress?>
    suspend fun start()
    suspend fun stop()
    suspend fun syncNow()
}

fun SyncStatus.label(): String = when (this) {
    SyncStatus.NotConfigured -> "Mimir: not connected"
    SyncStatus.Idle -> "Mimir: up to date"
    SyncStatus.Syncing -> "Mimir: syncing"
    SyncStatus.Offline -> "Mimir: waiting for network"
    is SyncStatus.Error -> "Mimir: error — $message"
}
