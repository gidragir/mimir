package dev.mimir.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class SyncProgressTest {
    private val t0 = Instant.fromEpochSeconds(1_000_000)

    private fun applying(bytesDone: Long, bytesTotal: Long = 4000, phase: SyncPhase = SyncPhase.Applying) =
        SyncProgress(
            phase = phase,
            startedAt = t0,
            applyingStartedAt = t0,
            bytesTotal = bytesTotal,
            bytesDone = bytesDone,
        )

    @Test
    fun offlineLabel() = assertEquals("Mimir: waiting for network", SyncStatus.Offline.label())

    @Test
    fun notConfiguredLabelUnchanged() = assertEquals("Mimir: not connected", SyncStatus.NotConfigured.label())

    @Test
    fun etaNullBeforeTwoSeconds() = assertNull(applying(bytesDone = 100).etaSeconds(t0 + 1.seconds))

    @Test
    fun etaNullWhenNothingDone() = assertNull(applying(bytesDone = 0).etaSeconds(t0 + 10.seconds))

    @Test
    fun etaProportionalToRemainingBytes() =
        assertEquals(30L, applying(bytesDone = 1000).etaSeconds(t0 + 10.seconds))

    @Test
    fun etaNullOutsideApplying() =
        assertNull(applying(bytesDone = 1000, phase = SyncPhase.Scanning).etaSeconds(t0 + 10.seconds))
}
