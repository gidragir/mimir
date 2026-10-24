package dev.mimir.daemon

import dev.mimir.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ProgressDtoTest {
    @Test
    fun `progress dto round trips`() {
        val t = Instant.parse("2026-10-24T12:00:00Z")
        val model = SyncProgress(
            phase = SyncPhase.Applying, startedAt = t, finishedAt = null, applyingStartedAt = t,
            scanned = 10, total = 5, done = 2, bytesTotal = 100, bytesDone = 40,
            current = SyncItem("a.md", SyncItemAction.DeleteLocal),
            upcoming = listOf(SyncItem("b.md", SyncItemAction.Upload)),
            completed = listOf(CompletedItem("c.md", SyncItemAction.Download, t)),
            problems = listOf(SyncProblem("d.md", SyncItemAction.Conflict, SyncProblemKind.ReadFailed, "boom")),
        )
        val dto = model.toDto(t)
        assertEquals("applying", dto.phase)
        assertEquals("delete_local", dto.current?.action)
        assertEquals("read_failed", dto.problems.single().kind)
        assertEquals(model, dto.toModel())
    }
}
