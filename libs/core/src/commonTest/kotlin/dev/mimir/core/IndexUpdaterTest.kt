package dev.mimir.core

import dev.mimir.core.sync.FileSyncRecord
import dev.mimir.core.sync.IndexUpdater
import dev.mimir.core.sync.SyncAction
import dev.mimir.core.sync.SyncIndex
import dev.mimir.core.sync.SyncStateStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class IndexUpdaterTest {

    private val now = Instant.fromEpochMilliseconds(1729720000000L)

    private fun record(path: String) = FileSyncRecord(
        relativePath = path,
        localId = VaultFileId("local-$path"),
        baseHash = "hash-$path",
        localSize = 1L,
        localModifiedAt = now,
        remotePath = RemotePath(path),
        remoteRevision = "rev-1",
        remoteModifiedAt = now,
        remoteSize = 1L,
    )

    private class CountingStore : SyncStateStore {
        val saved = mutableListOf<SyncIndex>()
        override suspend fun load(): SyncIndex = saved.lastOrNull() ?: SyncIndex()
        override suspend fun save(index: SyncIndex) {
            saved += index
        }
    }

    @Test
    fun savesEveryNChangesAndOnFlush() = runTest {
        val store = CountingStore()
        val updater = IndexUpdater(store, SyncIndex(), saveEvery = 100)

        for (i in 1..250) updater.update { it.withRecord(record("f$i.md")) }

        assertEquals(listOf(100, 200), store.saved.map { it.records.size })
        assertEquals(250, updater.current.records.size)

        updater.flush()

        assertEquals(listOf(100, 200, 250), store.saved.map { it.records.size })
    }

    @Test
    fun flushWithoutChangesDoesNotSave() = runTest {
        val store = CountingStore()
        val updater = IndexUpdater(store, SyncIndex(records = mapOf("a.md" to record("a.md"))), saveEvery = 100)

        updater.flush()
        assertEquals(0, store.saved.size)
        assertEquals(setOf("a.md"), updater.current.records.keys)

        // после сохранения по порогу и flush — повторный flush тоже ничего не пишет
        repeat(100) { i -> updater.update { it.withRecord(record("f$i.md")) } }
        assertEquals(1, store.saved.size)
        updater.flush()
        updater.flush()
        assertEquals(1, store.saved.size)
    }

    @Test
    fun updateAllSavesBatchOnce() = runTest {
        val store = CountingStore()
        val updater = IndexUpdater(store, SyncIndex(), saveEvery = 100)
        val actions = (1..250).map { i -> SyncAction.UpdateMetadata("f$i.md", record("f$i.md")) }

        updater.updateAll(actions)
        updater.flush()

        assertEquals(250, updater.current.records.size)
        assertEquals(1, store.saved.size)
    }
}
