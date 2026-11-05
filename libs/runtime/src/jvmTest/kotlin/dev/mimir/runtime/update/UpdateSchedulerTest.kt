package dev.mimir.runtime.update

import dev.mimir.core.update.DownloadedAsset
import dev.mimir.core.update.SemVer
import dev.mimir.core.update.UpdateApplyResult
import dev.mimir.core.update.UpdateCheckResult
import dev.mimir.core.update.UpdateManager
import dev.mimir.core.update.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateSchedulerTest {
    private class FakeManager(
        var next: () -> UpdateCheckResult = { UpdateCheckResult.NoUpdate(SemVer.parse("1.0.0")) },
    ) : UpdateManager {
        override val state: StateFlow<UpdateState> = MutableStateFlow(UpdateState.Idle())
        var checks = 0

        override suspend fun checkForUpdates(force: Boolean): UpdateCheckResult {
            checks++
            return next()
        }

        override suspend fun downloadUpdate(): Result<DownloadedAsset> = error("unused")
        override suspend fun applyUpdate(): UpdateApplyResult = error("unused")
        override fun dismiss() = Unit
    }

    /** Планировщик на виртуальном времени; scope отменяется в finally, иначе упавший ассерт зациклит runTest. */
    private suspend fun TestScope.withScheduler(
        manager: UpdateManager,
        busy: () -> Boolean = { false },
        block: suspend (UpdateScheduler) -> Unit,
    ) {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        try {
            block(UpdateScheduler(manager, scope, isSyncBusy = busy, clock = virtualClock()))
        } finally {
            scope.cancel()
        }
    }

    private fun TestScope.virtualClock() = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(testScheduler.currentTime)
    }

    private suspend fun TestScope.advance(by: kotlin.time.Duration) {
        advanceTimeBy(by)
        runCurrent()
    }

    @Test
    fun firstCheckAfter30sThenEvery24h() = runTest {
        val m = FakeManager()
        withScheduler(m) { s ->
            s.start()
            advance(29.seconds)
            assertEquals(0, m.checks)
            advance(2.seconds)
            assertEquals(1, m.checks)
            advance(23.hours + 59.minutes)
            assertEquals(1, m.checks)
            advance(2.minutes)
            assertEquals(2, m.checks)
        }
    }

    @Test
    fun skipsWhileSyncRunningAndRechecksSoon() = runTest {
        val m = FakeManager()
        var busy = true
        withScheduler(m, { busy }) { s ->
            s.start()
            advance(31.seconds)
            advance(30.seconds)
            assertEquals(0, m.checks)
            busy = false
            advance(UpdateScheduler.DEFAULT_BUSY_RECHECK + 1.seconds)
            assertEquals(1, m.checks)
        }
    }

    @Test
    fun retryAfterBacksOffAtLeastThatLong() = runTest {
        val m = FakeManager { UpdateCheckResult.Error("rate", retryAfterSeconds = 7200) }
        withScheduler(m) { s ->
            s.start()
            advance(31.seconds)
            assertEquals(1, m.checks)
            advance(2.hours - 10.seconds)
            assertEquals(1, m.checks)
            advance(15.seconds)
            assertEquals(2, m.checks)
        }
    }

    @Test
    fun plainErrorRetriesAfterErrorBackoff() = runTest {
        val m = FakeManager { UpdateCheckResult.Error("offline") }
        withScheduler(m) { s ->
            s.start()
            advance(31.seconds)
            assertEquals(1, m.checks)
            advance(UpdateScheduler.DEFAULT_ERROR_RETRY + 1.seconds)
            assertEquals(2, m.checks)
        }
    }

    @Test
    fun thrownExceptionDoesNotKillLoop() = runTest {
        var n = 0
        val m = FakeManager { if (n++ == 0) error("boom") else UpdateCheckResult.NoUpdate(SemVer.parse("1.0.0")) }
        withScheduler(m) { s ->
            s.start()
            advance(31.seconds)
            advance(UpdateScheduler.DEFAULT_ERROR_RETRY + 1.seconds)
            assertEquals(2, m.checks)
        }
    }

    @Test
    fun stopCancelsLoop() = runTest {
        val m = FakeManager()
        withScheduler(m) { s ->
            s.start()
            s.stop()
            advance(48.hours)
            assertEquals(0, m.checks)
        }
    }

    @Test
    fun secondStartDoesNotRestartTimer() = runTest {
        val m = FakeManager()
        withScheduler(m) { s ->
            s.start()
            advance(20.seconds)
            s.start() // не должен сбросить 30 с
            advance(11.seconds)
            assertEquals(1, m.checks)
            s.start()
            advance(23.hours)
            assertEquals(1, m.checks)
            advance(2.hours)
            assertEquals(2, m.checks)
        }
    }

    @Test
    fun startAfterStopRestartsSchedule() = runTest {
        val m = FakeManager()
        withScheduler(m) { s ->
            s.start()
            s.stop()
            s.start()
            advance(31.seconds)
            assertEquals(1, m.checks)
        }
    }

    @Test
    fun stopStartKeepsOriginalDeadline() = runTest {
        val m = FakeManager()
        withScheduler(m) { s ->
            s.start()
            advance(10.seconds)
            s.stop()
            advance(5.seconds)
            s.start() // t=15 с, срок остаётся 30 с
            advance(14.seconds)
            assertEquals(0, m.checks)
            advance(2.seconds)
            assertEquals(1, m.checks)
        }
    }

    @Test
    fun stopStartAfterCheckKeepsIntervalDeadline() = runTest {
        val m = FakeManager()
        withScheduler(m) { s ->
            s.start()
            advance(31.seconds) // проверка в 30 с, следующая в 24 ч 30 с
            s.stop()
            advance(1.hours)
            s.start()
            advance(22.hours)
            assertEquals(1, m.checks)
            advance(2.hours)
            assertEquals(2, m.checks)
        }
    }

    @Test
    fun startLongAfterDeadlineFiresImmediately() = runTest {
        val m = FakeManager()
        withScheduler(m) { s ->
            s.start()
            advance(31.seconds)
            s.stop()
            advance(25.hours)
            s.start()
            runCurrent()
            assertEquals(2, m.checks)
        }
    }
}
