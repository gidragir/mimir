package dev.mimir.daemon

import dev.mimir.core.SyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

class FakeDaemonService : DaemonService {
    override val status = MutableStateFlow<SyncStatus>(SyncStatus.NotConfigured)
    override var vaultPath: String? = null
    override var isLocked: Boolean = true
    override var lastSyncTime: Instant? = null

    /** `true` — запуск принят, `false` — уже идёт, `null` — не сконфигурировано. */
    var syncAccepted: Boolean? = null
    var syncCalls = 0
    var unlockedWith: String? = null

    override suspend fun triggerSync(): Boolean {
        syncCalls++
        return syncAccepted ?: throw SyncNotConfiguredException("not configured")
    }

    override suspend fun unlock(password: String) {
        unlockedWith = password
        isLocked = false
    }
}
