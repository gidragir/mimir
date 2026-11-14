package dev.mimir.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class StubSyncEngine : SyncEngine {
    override val status: StateFlow<SyncStatus> = MutableStateFlow<SyncStatus>(SyncStatus.NotConfigured).asStateFlow()
    override val progress: StateFlow<SyncProgress?> = MutableStateFlow<SyncProgress?>(null).asStateFlow()

    override suspend fun syncNow() = throw NotImplementedError("Sync engine is not implemented yet")
}
