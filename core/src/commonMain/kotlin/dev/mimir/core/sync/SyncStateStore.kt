package dev.mimir.core.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface SyncStateStore {
    suspend fun load(): SyncIndex
    suspend fun save(index: SyncIndex)
}

class InMemorySyncStateStore(initial: SyncIndex = SyncIndex()) : SyncStateStore {
    private val mutex = Mutex()
    private var state: SyncIndex = initial

    override suspend fun load(): SyncIndex = mutex.withLock { state }

    override suspend fun save(index: SyncIndex): Unit = mutex.withLock {
        state = index
    }
}
