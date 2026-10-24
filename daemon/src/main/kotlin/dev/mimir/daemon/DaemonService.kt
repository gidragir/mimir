package dev.mimir.daemon

import dev.mimir.core.SyncStatus
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/** То, что HTTP API требует от демона; реализуется [DaemonRuntime]. */
interface DaemonService {
    val status: StateFlow<SyncStatus>
    val vaultPath: String?
    val isLocked: Boolean
    val lastSyncTime: Instant?

    /** `false`, если синхронизация уже идёт; [SyncNotConfiguredException], если синхронизировать пока нечего. */
    suspend fun triggerSync(): Boolean
    suspend fun unlock(password: String)
}
