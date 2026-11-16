package dev.mimir.ui

import dev.mimir.core.DeletionDecision
import dev.mimir.core.SyncEngine
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import dev.mimir.ui.model.EngineMimirUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EngineMimirUiModelTest {
    private class RecordingSyncEngine : SyncEngine {
        override val status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
        override val progress = MutableStateFlow<SyncProgress?>(null)
        val decisions = mutableListOf<DeletionDecision>()
        var syncCalls = 0

        override suspend fun resolvePendingDeletions(decision: DeletionDecision) {
            decisions.add(decision)
        }

        override suspend fun syncNow() {
            syncCalls++
        }
    }

    @Test
    fun `resolveDeletions Keep triggers follow-up upload sync`() = runTest {
        val engine = RecordingSyncEngine()
        val model = EngineMimirUiModel(engine)

        model.resolveDeletions(DeletionDecision.Keep)

        assertEquals(listOf(DeletionDecision.Keep), engine.decisions)
        assertEquals(2, engine.syncCalls)
    }

    @Test
    fun `resolveDeletions Delete runs single sync`() = runTest {
        val engine = RecordingSyncEngine()
        val model = EngineMimirUiModel(engine)

        model.resolveDeletions(DeletionDecision.Delete)

        assertEquals(listOf(DeletionDecision.Delete), engine.decisions)
        assertEquals(1, engine.syncCalls)
    }
}
