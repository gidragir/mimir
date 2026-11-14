package dev.mimir.core.testing

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** Управляемые часы для детерминированных тестов без реального времени. */
class TestClock(
    var now: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L),
) : Clock {
    override fun now(): Instant = now

    fun advanceBy(duration: Duration) {
        now += duration
    }
}
