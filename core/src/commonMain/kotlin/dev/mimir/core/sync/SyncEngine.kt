package dev.mimir.core

import kotlinx.coroutines.flow.StateFlow

sealed interface SyncStatus {
    data object NotConfigured : SyncStatus
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data class Error(val message: String) : SyncStatus
}

interface SyncEngine {
    val status: StateFlow<SyncStatus>
    suspend fun start()
    suspend fun stop()
    suspend fun syncNow()
}

fun SyncStatus.label(): String = when (this) {
    SyncStatus.NotConfigured -> "Mimir: not connected"
    SyncStatus.Idle -> "Mimir: up to date"
    SyncStatus.Syncing -> "Mimir: syncing"
    is SyncStatus.Error -> "Mimir: error — $message"
}
