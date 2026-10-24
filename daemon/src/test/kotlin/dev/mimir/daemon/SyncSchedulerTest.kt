package dev.mimir.daemon

import dev.mimir.core.LocalEntry
import dev.mimir.core.SyncEngine
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import dev.mimir.core.VaultChange
import dev.mimir.core.VaultFileId
import dev.mimir.core.InMemoryRemoteStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import dev.mimir.core.RemotePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SyncSchedulerTest {
    private class FakeEngine : SyncEngine {
        override val status: StateFlow<SyncStatus> = MutableStateFlow(SyncStatus.Idle)
        override val progress: StateFlow<SyncProgress?> = MutableStateFlow(null)
        var calls = 0
        var gate: CompletableDeferred<Unit>? = null
        var failNext = false
        var cancelled = false

        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun syncNow() {
            calls++
            try {
                gate?.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            }
            if (failNext) {
                failNext = false
                error("network down")
            }
        }
    }

    private val interval = 30.seconds
    private val entry = LocalEntry(VaultFileId("fs:a.md"), "a.md", 1, Instant.fromEpochSeconds(0))

    private fun TestScope.scheduler(
        engine: FakeEngine,
        feed: InMemoryRemoteStorage? = null,
        local: MutableSharedFlow<VaultChange>? = null,
    ) = SyncScheduler(
        syncEngine = engine,
        syncInterval = interval,
        scope = backgroundScope,
        localChanges = local ?: MutableSharedFlow(),
        remoteFeed = feed,
    )

    @Test
    fun `second trigger is rejected while a sync is running`() = runTest {
        val engine = FakeEngine().apply { gate = CompletableDeferred() }
        val scheduler = scheduler(engine)

        assertTrue(scheduler.triggerSync())
        runCurrent()
        assertFalse(scheduler.triggerSync())
        assertEquals(1, engine.calls)

        engine.gate!!.complete(Unit)
        runCurrent()
        engine.gate = null
        assertTrue(scheduler.triggerSync())
        runCurrent()
        assertEquals(2, engine.calls)
        assertNotNull(scheduler.lastSyncTime)
    }

    @Test
    fun `timer syncs immediately and then on every interval`() = runTest {
        val engine = FakeEngine()
        val scheduler = scheduler(engine).also { it.start() }

        runCurrent()
        assertEquals(1, engine.calls)
        advanceTimeBy(interval * 3 + 1.seconds)
        runCurrent()
        assertEquals(4, engine.calls)
        scheduler.stop()
    }

    @Test
    fun `with a change feed the timer only syncs when the remote changed`() = runTest {
        val engine = FakeEngine()
        val remote = InMemoryRemoteStorage()
        val scheduler = scheduler(engine, feed = remote).also { it.start() }

        runCurrent()
        assertEquals(1, engine.calls) // начальная синхронизация
        advanceTimeBy(interval + 1.seconds)
        runCurrent()
        assertEquals(1, engine.calls) // ничего не изменилось

        remote.write(RemotePath("x.md"), Buffer().also { it.write("x".encodeToByteArray()) })
        advanceTimeBy(interval)
        runCurrent()
        assertEquals(2, engine.calls)
        scheduler.stop()
    }

    @Test
    fun `local change triggers a sync and changes during a sync queue exactly one rerun`() = runTest {
        val engine = FakeEngine().apply { gate = CompletableDeferred() }
        val local = MutableSharedFlow<VaultChange>()
        val scheduler = scheduler(engine, local = local).also { it.start() }
        runCurrent()
        assertEquals(1, engine.calls) // стартовая синхронизация ещё идёт

        repeat(3) { local.emit(VaultChange.Modified(entry.id, entry)) }
        runCurrent()
        assertEquals(1, engine.calls)

        engine.gate!!.complete(Unit)
        runCurrent()
        assertEquals(2, engine.calls)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(2, engine.calls)
        scheduler.stop()
    }

    @Test
    fun `broken local watcher is reported and does not stop the timer`() = runTest {
        val engine = FakeEngine()
        val scheduler = SyncScheduler(
            syncEngine = engine,
            syncInterval = interval,
            scope = backgroundScope,
            localChanges = kotlinx.coroutines.flow.flow { throw java.io.IOException("watch failed") },
        ).also { it.start() }

        runCurrent()
        advanceTimeBy(interval + 1.seconds)
        runCurrent()

        assertEquals("watch failed", scheduler.watcherError)
        assertEquals(2, engine.calls)
        scheduler.stop()
    }

    @Test
    fun `with a broken local watcher the timer falls back to full syncs even when the change feed is quiet`() = runTest {
        val engine = FakeEngine()
        val scheduler = SyncScheduler(
            syncEngine = engine,
            syncInterval = interval,
            scope = backgroundScope,
            localChanges = kotlinx.coroutines.flow.flow { throw java.io.IOException("inotify limit reached") },
            remoteFeed = InMemoryRemoteStorage(),
        ).also { it.start() }

        runCurrent()
        assertEquals(1, engine.calls)
        advanceTimeBy(interval + 1.seconds)
        runCurrent()
        assertEquals(2, engine.calls)
        scheduler.stop()
    }

    @Test
    fun `stop cancels a running sync and releases the lock`() = runTest {
        val engine = FakeEngine().apply { gate = CompletableDeferred() }
        val scheduler = scheduler(engine)
        assertTrue(scheduler.triggerSync())
        runCurrent()

        scheduler.stop()
        runCurrent()

        assertTrue(engine.cancelled, "in-flight sync must not outlive its scheduler")
        assertTrue(scheduler.triggerSync())
    }

    @Test
    fun `failed sync does not kill the loop and does not set lastSyncTime`() = runTest {
        val engine = FakeEngine().apply { failNext = true }
        val scheduler = scheduler(engine).also { it.start() }

        runCurrent()
        assertEquals(1, engine.calls)
        assertNull(scheduler.lastSyncTime)

        advanceTimeBy(interval + 1.seconds)
        runCurrent()
        assertEquals(2, engine.calls)
        assertNotNull(scheduler.lastSyncTime)
        scheduler.stop()
    }
}
