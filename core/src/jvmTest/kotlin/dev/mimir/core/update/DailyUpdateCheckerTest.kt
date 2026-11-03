package dev.mimir.core.update

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

class DailyUpdateCheckerTest {
    private class Channel(var result: UpdateCheckResult) : UpdateChannel {
        var calls = 0
        override val name = "fake"
        override suspend fun checkForUpdates(currentVersion: SemVer, targetPlatform: TargetPlatform): UpdateCheckResult {
            calls++
            return result
        }
    }

    private var now = Instant.fromEpochSeconds(1_000_000)
    private val clock = object : Clock { override fun now() = now }
    private var stored: Long? = null
    private val v1 = SemVer.parse("1.0.0")

    private fun checker(channel: Channel): DailyUpdateChecker {
        val manager = DefaultUpdateManager(v1, TargetPlatform.OBSIDIAN_ANY, channel, FakeUpdateApplier())
        return DailyUpdateChecker(manager, clock, { stored }, { stored = it })
    }

    @Test
    fun runsOncePerIntervalAndPersistsTime() = runTest {
        val channel = Channel(UpdateCheckResult.NoUpdate(v1))
        val c = checker(channel)
        assertIs<UpdateCheckResult.NoUpdate>(c.checkIfDue())
        assertEquals(1_000_000L, stored)
        now = Instant.fromEpochSeconds(1_000_000 + 3600)
        assertNull(c.checkIfDue())
        assertEquals(1, channel.calls)
        now = Instant.fromEpochSeconds(1_000_000 + 24 * 3600)
        assertIs<UpdateCheckResult.NoUpdate>(c.checkIfDue())
        assertEquals(2, channel.calls)
    }

    @Test
    fun errorDoesNotConsumeTheInterval() = runTest {
        val channel = Channel(UpdateCheckResult.Error("offline"))
        val c = checker(channel)
        assertIs<UpdateCheckResult.Error>(c.checkIfDue())
        assertNull(stored)
        channel.result = UpdateCheckResult.NoUpdate(v1)
        assertIs<UpdateCheckResult.NoUpdate>(c.checkIfDue())
        assertEquals(2, channel.calls)
    }

    @Test
    fun clockGoingBackwardsTriggersCheck() = runTest {
        stored = 2_000_000
        val channel = Channel(UpdateCheckResult.NoUpdate(v1))
        assertIs<UpdateCheckResult.NoUpdate>(checker(channel).checkIfDue())
    }

    @Test
    fun storeFailureNeverEscapes() = runTest {
        val channel = Channel(UpdateCheckResult.NoUpdate(v1))
        val manager = DefaultUpdateManager(v1, TargetPlatform.OBSIDIAN_ANY, channel, FakeUpdateApplier())
        val c = DailyUpdateChecker(manager, clock, { error("read") }, { error("write") })
        assertIs<UpdateCheckResult.NoUpdate>(c.checkIfDue())
    }
}
