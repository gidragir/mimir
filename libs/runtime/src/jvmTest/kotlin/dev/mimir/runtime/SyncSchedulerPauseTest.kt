package dev.mimir.runtime

import dev.mimir.core.SyncEngine
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** pause/resume планировщика: виртуальное время и ворота, без advanceUntilIdle (внутри бесконечный тикер). */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncSchedulerPauseTest {
    private class GatedEngine : SyncEngine {
        override val status: StateFlow<SyncStatus> = MutableStateFlow(SyncStatus.Idle)
        override val progress: StateFlow<SyncProgress?> = MutableStateFlow(null)
        var calls = 0
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun syncNow() {
            calls++
            gate?.await()
        }
    }

    private fun TestScope.scheduler(engine: GatedEngine) =
        SyncScheduler(engine, syncInterval = 30.seconds, scope = backgroundScope)

    @Test
    fun `pause waits for the running sync and blocks new runs until resume`() = runTest {
        val engine = GatedEngine().apply { gate = CompletableDeferred() }
        val scheduler = scheduler(engine)
        assertTrue(scheduler.triggerSync())
        runCurrent()
        assertEquals(1, engine.calls)

        val paused = async { scheduler.pause(60.seconds) }
        runCurrent()
        assertFalse(paused.isCompleted, "must wait for the in-flight sync")

        engine.gate!!.complete(Unit)
        runCurrent()
        assertTrue(paused.await())
        assertFalse(scheduler.triggerSync(), "no new runs while paused")
        assertEquals(1, engine.calls)

        scheduler.resume()
        runCurrent()
        assertTrue(scheduler.triggerSync(), "runs again after resume")
        runCurrent()
        assertEquals(2, engine.calls)
    }

    @Test
    fun `pause times out on a long sync without holding anything`() = runTest {
        val engine = GatedEngine().apply { gate = CompletableDeferred() }
        val scheduler = scheduler(engine)
        scheduler.triggerSync()
        runCurrent()

        val paused = async { scheduler.pause(60.seconds) }
        advanceTimeBy(59.seconds)
        runCurrent()
        assertFalse(paused.isCompleted)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertFalse(paused.await(), "timeout => not paused")

        engine.gate!!.complete(Unit)
        runCurrent()
        assertTrue(scheduler.triggerSync(), "sync mutex is free after the failed pause")
    }

    @Test
    fun `a request arriving during pause reruns once after resume`() = runTest {
        val engine = GatedEngine()
        val scheduler = scheduler(engine)
        assertTrue(scheduler.pause(10.seconds))

        scheduler.start() // тик просит синхронизацию, но мьютекс удерживается
        runCurrent()
        assertEquals(0, engine.calls)

        scheduler.resume()
        runCurrent()
        assertEquals(1, engine.calls)
        scheduler.stop()
    }
}
