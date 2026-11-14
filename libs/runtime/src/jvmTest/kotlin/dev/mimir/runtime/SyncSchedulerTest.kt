package dev.mimir.runtime

import dev.mimir.core.LocalEntry
import dev.mimir.core.VaultChange
import dev.mimir.core.VaultFileId
import dev.mimir.core.InMemoryRemoteStorage
import dev.mimir.core.testing.FakeSyncEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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
    private class EngineControl {
        var gate: CompletableDeferred<Unit>? = null
        var failNext = false
        var cancelled = false
        val engine = FakeSyncEngine {
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
        val calls: Int get() = engine.syncNowCalls
    }

    private val interval = 30.seconds
    private val entry = LocalEntry(VaultFileId("fs:a.md"), "a.md", 1, Instant.fromEpochSeconds(0))

    private fun TestScope.scheduler(
        engine: EngineControl,
        feed: InMemoryRemoteStorage? = null,
        local: MutableSharedFlow<VaultChange>? = null,
    ) = SyncScheduler(
        syncEngine = engine.engine,
        syncInterval = interval,
        scope = backgroundScope,
        localChanges = local ?: MutableSharedFlow(),
        remoteFeed = feed,
    )

    @Test
    fun `second trigger is rejected while a sync is running`() = runTest {
        val engine = EngineControl().apply { gate = CompletableDeferred() }
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
        val engine = EngineControl()
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
        val engine = EngineControl()
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
        val engine = EngineControl().apply { gate = CompletableDeferred() }
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
        val engine = EngineControl()
        val scheduler = SyncScheduler(
            syncEngine = engine.engine,
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
        val engine = EngineControl()
        val scheduler = SyncScheduler(
            syncEngine = engine.engine,
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
        val engine = EngineControl().apply { gate = CompletableDeferred() }
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
        val engine = EngineControl().apply { failNext = true }
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

    @Test
    fun `tick network failure schedules full sync`() = runTest {
        val engine = EngineControl()
        val feed = object : dev.mimir.core.ChangeFeedStorage by InMemoryRemoteStorage() {
            override suspend fun getChanges(sinceToken: String): dev.mimir.core.RemoteChangesResult =
                throw dev.mimir.core.RemoteUnavailableException("network down")
        }
        val scheduler = SyncScheduler(
            syncEngine = engine.engine,
            syncInterval = interval,
            scope = backgroundScope,
            localChanges = MutableSharedFlow(),
            remoteFeed = feed,
        ).also { it.start() }

        runCurrent()
        assertEquals(1, engine.calls) // стартовая
        advanceTimeBy(interval + 1.seconds)
        runCurrent()
        assertEquals(1, engine.calls) // getChanges упал, повтор отложен на следующий тик
        advanceTimeBy(interval)
        runCurrent()
        assertEquals(2, engine.calls)
        scheduler.stop()
    }

    @Test
    fun `runNow waits for an in-flight run instead of starting a second`() = runTest {
        var active = 0
        var maxActive = 0
        var runs = 0
        val engine = FakeSyncEngine {
            active++
            maxActive = maxOf(maxActive, active)
            kotlinx.coroutines.delay(1_000)
            runs++
            active--
        }
        val scheduler = SyncScheduler(engine, interval, backgroundScope)

        val first = async { scheduler.runNow() }
        val second = async { scheduler.runNow() }
        assertTrue(first.await())
        assertTrue(second.await())

        assertEquals(2, runs)
        assertEquals(1, maxActive, "runs must never overlap")
    }

    @Test
    fun `runNow waits for a scheduler run started by trigger`() = runTest {
        val engine = EngineControl().apply { gate = CompletableDeferred() }
        val scheduler = scheduler(engine)
        assertTrue(scheduler.triggerSync())
        runCurrent()

        val waiting = async { scheduler.runNow() }
        runCurrent()
        assertEquals(1, engine.calls, "second run must wait for the first")

        engine.gate!!.complete(Unit)
        assertTrue(waiting.await())
        assertEquals(2, engine.calls)
    }

    @Test
    fun `runNow rethrows the sync failure`() = runTest {
        val engine = EngineControl().apply { failNext = true }
        val scheduler = scheduler(engine)

        val failure = runCatching { scheduler.runNow() }.exceptionOrNull()

        assertEquals("network down", failure?.message)
        assertEquals("network down", scheduler.lastError)
        assertTrue(scheduler.triggerSync(), "lock must be released after a failed run")
    }

    @Test
    fun `pendingLocalChanges clears after successful run`() = runTest {
        val engine = EngineControl().apply { failNext = true }
        val local = MutableSharedFlow<VaultChange>()
        val scheduler = scheduler(engine, local = local).also { it.start() }
        runCurrent() // стартовый прогон падает
        assertFalse(scheduler.pendingLocalChanges.value)

        engine.gate = CompletableDeferred()
        local.emit(VaultChange.Modified(entry.id, entry))
        runCurrent()
        assertTrue(scheduler.pendingLocalChanges.value, "local change must be pending while its run is in flight")

        engine.gate!!.complete(Unit)
        runCurrent()
        assertFalse(scheduler.pendingLocalChanges.value)
        scheduler.stop()
    }

    @Test
    fun `pendingLocalChanges stays after a failed run`() = runTest {
        val engine = EngineControl()
        val local = MutableSharedFlow<VaultChange>()
        val scheduler = scheduler(engine, local = local).also { it.start() }
        runCurrent()

        engine.failNext = true
        local.emit(VaultChange.Modified(entry.id, entry))
        runCurrent()

        assertTrue(scheduler.pendingLocalChanges.value)
        scheduler.stop()
    }

    @Test
    fun `local change during runNow triggers rerun after lock is released`() = runTest {
        val engine = EngineControl()
        val local = MutableSharedFlow<VaultChange>()
        val scheduler = scheduler(engine, local = local).also { it.start() }
        runCurrent()
        assertEquals(1, engine.calls)

        val runNowGate = CompletableDeferred<Unit>()
        engine.gate = runNowGate
        val inFlight = async { scheduler.runNow() }
        runCurrent()
        assertEquals(2, engine.calls)

        local.emit(VaultChange.Modified(entry.id, entry))
        runCurrent()
        assertTrue(scheduler.pendingLocalChanges.value)
        assertEquals(2, engine.calls)

        runNowGate.complete(Unit)
        assertTrue(inFlight.await())
        runCurrent()

        assertEquals(3, engine.calls, "rerun requested during runNow must execute after lock is released")
        assertFalse(scheduler.pendingLocalChanges.value)
        scheduler.stop()
    }

    @Test
    fun `stop with cancelRunner false keeps in-flight sync running`() = runTest {
        val engine = EngineControl().apply { gate = CompletableDeferred() }
        val scheduler = scheduler(engine)

        assertTrue(scheduler.triggerSync())
        runCurrent()
        assertEquals(1, engine.calls)

        scheduler.stop(cancelRunner = false)
        runCurrent()
        assertFalse(engine.cancelled, "in-flight runner must not be cancelled")

        engine.gate!!.complete(Unit)
        runCurrent()
        assertEquals(1, engine.calls)
        kotlin.test.assertNotNull(scheduler.lastSyncTime)
    }

    @Test
    fun `start does not queue duplicate run when sync starts while getLatestChangeToken is in flight`() = runTest {
        val tokenGate = CompletableDeferred<String>()
        val feed = object : dev.mimir.core.ChangeFeedStorage by InMemoryRemoteStorage() {
            override suspend fun getLatestChangeToken(): String = tokenGate.await()
        }
        val syncGate = CompletableDeferred<Unit>()
        val engine = EngineControl().apply { gate = syncGate }
        val scheduler = SyncScheduler(
            syncEngine = engine.engine,
            syncInterval = interval,
            scope = backgroundScope,
            localChanges = MutableSharedFlow(),
            remoteFeed = feed,
        )

        scheduler.start()
        runCurrent()
        assertEquals(0, engine.calls, "tick is still waiting for getLatestChangeToken")

        // Параллельный вызов runNow (как в MimirAppLifecycleObserver.onStart или мастере настройки)
        val runNowDeferred = async { scheduler.runNow() }
        runCurrent()
        assertEquals(1, engine.calls)

        // getLatestChangeToken завершается во время уже идущего полного прогона
        tokenGate.complete("token-1")
        runCurrent()

        // Завершаем первый прогон — второй холостой прогон запускаться не должен
        syncGate.complete(Unit)
        assertTrue(runNowDeferred.await())
        runCurrent()

        assertEquals(1, engine.calls, "initial token bootstrap must not queue a second back-to-back sync")
        scheduler.stop()
    }

    @Test
    fun `stop with cancelRunner true marks interrupted sync for retry on next start`() = runTest {
        val engine = EngineControl().apply { gate = CompletableDeferred() }
        val remote = InMemoryRemoteStorage()
        val scheduler = scheduler(engine, feed = remote).also { it.start() }

        runCurrent()
        assertEquals(1, engine.calls)
        assertNull(scheduler.lastSyncTime)

        // Приложение уходит в фон: отменяем активный раннер
        scheduler.stop(cancelRunner = true)
        runCurrent()
        assertTrue(engine.cancelled)
        assertNull(scheduler.lastSyncTime)

        // Возврат в интерактивный режим без новых событий в ленте изменений Drive
        engine.gate = null
        engine.cancelled = false
        scheduler.start()
        runCurrent()

        assertEquals(2, engine.calls, "interrupted sync must be retried on next start even if remote feed has no new changes")
        assertNotNull(scheduler.lastSyncTime)
        scheduler.stop()
    }
}
