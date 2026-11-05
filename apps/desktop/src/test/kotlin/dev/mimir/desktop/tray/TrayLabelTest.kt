package dev.mimir.desktop.tray

import dev.mimir.core.SyncPhase
import dev.mimir.core.SyncProblem
import dev.mimir.core.SyncItemAction
import dev.mimir.core.SyncProblemKind
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class TrayLabelTest {
    private val t0 = Instant.parse("2026-10-24T10:00:00Z")

    private fun applying(done: Int, total: Int) =
        SyncProgress(SyncPhase.Applying, startedAt = t0, applyingStartedAt = t0, total = total, done = done)

    private fun scanning() = SyncProgress(SyncPhase.Scanning, startedAt = t0)

    private fun finished(problems: Int) = SyncProgress(
        SyncPhase.Finished,
        startedAt = t0,
        finishedAt = t0,
        problems = List(problems) { SyncProblem("f$it.md", SyncItemAction.Upload, SyncProblemKind.Unknown, "x") },
    )

    @Test fun trayShowsCountersWhileApplying() =
        assertEquals("Mimir: syncing 12/87", trayLabel(SyncStatus.Syncing, applying(12, 87)))

    @Test fun trayShowsPlainSyncingBeforePlan() =
        assertEquals("Mimir: syncing", trayLabel(SyncStatus.Syncing, scanning()))

    @Test fun trayShowsProblemsAfterRun() =
        assertEquals("Mimir: up to date (2 problems)", trayLabel(SyncStatus.Idle, finished(problems = 2)))

    @Test fun trayProblemSingular() =
        assertEquals("Mimir: up to date (1 problem)", trayLabel(SyncStatus.Idle, finished(problems = 1)))

    @Test fun trayNotConnectedUnchanged() =
        assertEquals("Mimir: not connected", trayLabel(SyncStatus.NotConfigured, null))
}
