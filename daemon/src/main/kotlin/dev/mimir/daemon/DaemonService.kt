package dev.mimir.daemon

import dev.mimir.core.SyncStatus
import dev.mimir.drive.api.GoogleDeviceCodeResponse
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/** То, что HTTP API требует от демона; реализуется [DaemonRuntime]. */
interface DaemonService {
    val status: StateFlow<SyncStatus>
    val vaultPath: String?
    val isLocked: Boolean
    val lastSyncTime: Instant?
    val accountEmail: String?

    /** `false`, если синхронизация уже идёт; [SyncNotConfiguredException], если синхронизировать пока нечего. */
    suspend fun triggerSync(): Boolean
    suspend fun unlock(password: String)

    suspend fun conflicts(): List<ConflictFileEntry>

    /** [IllegalArgumentException] — пара путей некорректна, [NoSuchElementException] — копии нет, [SyncNotConfiguredException] — нет vault. */
    suspend fun resolveConflict(originalPath: String, conflictPath: String, keepConflict: Boolean)

    /** [SyncNotConfiguredException], если не задан Google client id. */
    suspend fun startDeviceLogin(): GoogleDeviceCodeResponse
    suspend fun pollDeviceLogin(deviceCode: String): DevicePoll
}

sealed interface DevicePoll {
    data object Pending : DevicePoll
    data class Authorized(val email: String?) : DevicePoll
    data class Expired(val reason: String) : DevicePoll
}
