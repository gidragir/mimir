package dev.mimir.core

import dev.mimir.core.sync.DefaultSyncEngine
import dev.mimir.core.sync.InMemorySyncStateStore
import dev.mimir.core.testing.InMemoryLocalVault
import dev.mimir.core.testing.TestClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SyncEngineRunStatsTest {
    @Test
    fun finishedProgressCarriesRunStatsAndOutcome() = runTest {
        val metrics = RecordingSyncMetrics(TestClock())
        val engine = DefaultSyncEngine(
            InMemoryLocalVault(),
            InMemoryRemoteStorage(),
            InMemorySyncStateStore(),
            metrics = metrics,
        )
        engine.syncNow()
        val p = assertNotNull(engine.progress.value)
        assertEquals(SyncPhase.Finished, p.phase)
        assertEquals("ok", assertNotNull(p.runStats).outcome)
    }
}
