package dev.mimir.core

import dev.mimir.core.sync.DefaultSyncEngine
import dev.mimir.core.sync.InMemorySyncStateStore
import dev.mimir.core.testing.CountingLocalVault
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MassDeletionGuardTest {

    private fun bufferOf(text: String): Buffer =
        Buffer().also { it.write(text.encodeToByteArray()) }

    private class Fixture {
        val innerRemote = InMemoryRemoteStorage()
        val remote = CountingRemoteStorage(innerRemote)
        val local = CountingLocalVault()
        val store = InMemorySyncStateStore()
        val engine = DefaultSyncEngine(local, remote, store)
    }

    private suspend fun Fixture.seedAndSync(count: Int) {
        for (i in 1..count) {
            local.write("note-$i.md", bufferOf("content-$i"))
        }
        engine.syncNow()
        assertEquals(count, store.load().records.size)
        local.reset()
        remote.reset()
    }

    private suspend fun Fixture.deleteRemotely(count: Int) {
        for (i in 1..count) {
            innerRemote.delete(RemotePath("note-$i.md"))
        }
    }

    @Test
    fun overThresholdStopsBeforeAnyDeletion() = runTest {
        val f = Fixture()
        f.seedAndSync(100)
        f.deleteRemotely(60)
        val indexBefore = f.store.load()

        val error = assertFailsWith<MassDeletionException> { f.engine.syncNow() }

        assertEquals(60, error.count)
        assertEquals(SyncStatus.NeedsConfirmation(60), f.engine.status.value)
        assertEquals(0, f.local.trashCalls)
        assertEquals(indexBefore, f.store.load())
    }

    @Test
    fun deleteDecisionAppliesOnce() = runTest {
        val f = Fixture()
        f.seedAndSync(100)
        f.deleteRemotely(60)
        assertFailsWith<MassDeletionException> { f.engine.syncNow() }

        f.engine.resolvePendingDeletions(DeletionDecision.Delete)
        f.engine.syncNow()

        assertEquals(60, f.local.trashCalls)
        assertEquals(SyncStatus.Idle, f.engine.status.value)
        assertEquals(40, f.store.load().records.size)
    }

    @Test
    fun keepDecisionPreservesFilesAndReuploadsNextRun() = runTest {
        val f = Fixture()
        f.seedAndSync(100)
        f.deleteRemotely(60)
        assertFailsWith<MassDeletionException> { f.engine.syncNow() }

        f.remote.reset()
        f.engine.resolvePendingDeletions(DeletionDecision.Keep)
        f.engine.syncNow()

        assertEquals(0, f.local.trashCalls)
        assertEquals(SyncStatus.Idle, f.engine.status.value)

        f.engine.syncNow()

        assertEquals(60, f.remote.writeCalls)
        assertEquals(100, f.store.load().records.size)
    }

    @Test
    fun smallVaultBelowFloorNeedsNoConfirmation() = runTest {
        val f = Fixture()
        f.seedAndSync(12)
        f.deleteRemotely(10)

        f.engine.syncNow()

        assertEquals(SyncStatus.Idle, f.engine.status.value)
        assertEquals(10, f.local.trashCalls)
    }
}
