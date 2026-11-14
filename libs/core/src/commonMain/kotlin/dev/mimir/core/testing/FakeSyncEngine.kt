package dev.mimir.core.testing

import dev.mimir.core.SyncEngine
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Управляемый тестовый двойник [SyncEngine] для проверки потребителей без реальной синхронизации. */
class FakeSyncEngine(
    initialStatus: SyncStatus = SyncStatus.Idle,
    initialProgress: SyncProgress? = null,
    private val onSync: suspend () -> Unit = {},
) : SyncEngine {
    private val _status = MutableStateFlow(initialStatus)
    override val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _progress = MutableStateFlow(initialProgress)
    override val progress: StateFlow<SyncProgress?> = _progress.asStateFlow()

    var syncNowCalls: Int = 0
        private set

    override suspend fun syncNow() {
        syncNowCalls++
        onSync()
    }

    fun setStatus(newStatus: SyncStatus) {
        _status.value = newStatus
    }

    fun setProgress(newProgress: SyncProgress?) {
        _progress.value = newProgress
    }
}
