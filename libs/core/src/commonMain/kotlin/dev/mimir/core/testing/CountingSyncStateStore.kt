package dev.mimir.core.testing

import dev.mimir.core.sync.InMemorySyncStateStore
import dev.mimir.core.sync.SyncIndex
import dev.mimir.core.sync.SyncStateStore

/** Декоратор [SyncStateStore], подсчитывающий количество сохранений индекса на диск. */
class CountingSyncStateStore(
    private val inner: SyncStateStore = InMemorySyncStateStore(),
) : SyncStateStore {
    var saves: Int = 0
        private set

    val saved = mutableListOf<SyncIndex>()

    override suspend fun load(): SyncIndex = inner.load()

    override suspend fun save(index: SyncIndex) {
        saves++
        saved += index
        inner.save(index)
    }

    fun reset() {
        saves = 0
        saved.clear()
    }
}
