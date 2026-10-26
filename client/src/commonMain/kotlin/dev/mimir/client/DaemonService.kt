package dev.mimir.client

import dev.mimir.core.ConflictFileEntry
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

interface DaemonService {
    val status: StateFlow<SyncStatus>
    val progress: StateFlow<SyncProgress?>
    val conflictsFlow: StateFlow<List<ConflictFileEntry>>
    val connectionStatus: StateFlow<DaemonConnectionStatus>
    val vaultPath: String?
    val isLocked: Boolean
    val lastSyncTime: Instant?
    val accountEmail: String?

    suspend fun triggerSync(): Boolean
    suspend fun conflicts(): List<ConflictFileEntry>
    suspend fun resolveConflict(originalPath: String, conflictPath: String, keepConflict: Boolean)
    suspend fun configureVault(vaultPath: String): Int
    suspend fun inspectVault(): DaemonVaultInspectionDto
    suspend fun unlock(password: String)
    suspend fun loginMethods(): Set<DaemonLoginMethod>
    suspend fun startBrowserLogin(): String
    suspend fun pollBrowserLogin(): DaemonBrowserLoginPoll
    suspend fun startDeviceLogin(): DaemonDeviceCodeDto
    suspend fun pollDeviceLogin(deviceCode: String): DaemonDevicePoll
}
