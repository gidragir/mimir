package dev.mimir.core.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateManagerTest {
    private val asset = UpdateAsset(
        "app.msi", "https://example.test/app.msi", 1000L, "hash", TargetPlatform.WINDOWS_X64, AssetKind.MSI,
    )
    private val release = UpdateRelease(
        version = SemVer.parse("0.2.0"),
        releaseNotes = "Notes",
        publishedAt = Instant.fromEpochSeconds(0),
        htmlUrl = "https://example.test/r",
        assets = listOf(asset),
    )
    private val current = SemVer.parse("0.1.0")

    private fun available(matched: UpdateAsset? = asset) = UpdateCheckResult.UpdateAvailable(release, matched)

    private val log = RecordingUpdateLogger()

    private fun manager(
        channel: FakeUpdateChannel,
        applier: FakeUpdateApplier = FakeUpdateApplier(),
    ) = DefaultUpdateManager(current, TargetPlatform.WINDOWS_X64, channel, applier, logger = log)

    @Test
    fun fullUpdateCycleTransitions() = runTest {
        val applier = FakeUpdateApplier(progressSteps = listOf(Triple(0.5f, 500L, 1000L)))
        val manager = manager(FakeUpdateChannel(available()), applier)
        assertIs<UpdateState.Idle>(manager.state.value)

        assertIs<UpdateCheckResult.UpdateAvailable>(manager.checkForUpdates())
        val availableState = assertIs<UpdateState.Available>(manager.state.value)
        assertEquals(asset, availableState.asset)
        assertEquals(ApplierCapability.DOWNLOAD_AND_LAUNCH, availableState.capability)

        val download = manager.downloadUpdate()
        assertTrue(download.isSuccess)
        val ready = assertIs<UpdateState.ReadyToInstall>(manager.state.value)
        assertSame(download.getOrThrow(), ready.downloadedAsset)

        assertIs<UpdateApplyResult.LaunchedInstaller>(manager.applyUpdate())
        assertIs<UpdateState.Installing>(manager.state.value)
        assertEquals(listOf(ready.downloadedAsset), applier.applied)
    }

    @Test
    fun progressIsForwardedToDownloadingState() = runTest {
        val gate = CompletableDeferred<Unit>()
        val applier = FakeUpdateApplier(
            progressSteps = listOf(Triple(0.5f, 500L, 1000L)),
            downloadGate = gate,
        )
        val manager = manager(FakeUpdateChannel(available()), applier)
        manager.checkForUpdates()

        val job = launch { manager.downloadUpdate() }
        runCurrent()
        val downloading = assertIs<UpdateState.Downloading>(manager.state.value)
        assertEquals(0.5f, downloading.progress)
        assertEquals(500L, downloading.bytesRead)
        assertEquals(1000L, downloading.totalBytes)

        gate.complete(Unit)
        job.join()
        assertIs<UpdateState.ReadyToInstall>(manager.state.value)
    }

    @Test
    fun noUpdateGoesIdleWithLastCheckedAndVersion() = runTest {
        val manager = manager(FakeUpdateChannel(UpdateCheckResult.NoUpdate(current)))
        assertIs<UpdateCheckResult.NoUpdate>(manager.checkForUpdates())
        val idle = assertIs<UpdateState.Idle>(manager.state.value)
        assertEquals(current, idle.latestKnownVersion)
        assertTrue(idle.lastCheckedAt != null)
    }

    @Test
    fun channelErrorSetsErrorStateAndReturnsError() = runTest {
        val error = UpdateCheckResult.Error("boom", retryAfterSeconds = 60)
        val manager = manager(FakeUpdateChannel(error))
        val result = manager.checkForUpdates()
        assertSame(error, result)
        val state = assertIs<UpdateState.Error>(manager.state.value)
        assertEquals("boom", state.message)
        assertIs<UpdateState.Idle>(state.previousState)
    }

    @Test
    fun channelExceptionBecomesErrorButCancellationIsRethrown() = runTest {
        val channel = FakeUpdateChannel(UpdateCheckResult.NoUpdate(current), throwable = IllegalStateException("io"))
        val manager = manager(channel)
        val result = assertIs<UpdateCheckResult.Error>(manager.checkForUpdates())
        assertEquals("io", result.message)
        assertIs<UpdateState.Error>(manager.state.value)

        channel.throwable = kotlinx.coroutines.CancellationException("cancelled")
        var rethrown = false
        try {
            manager.checkForUpdates()
        } catch (e: kotlinx.coroutines.CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
        assertIs<UpdateState.Error>(manager.state.value) // восстановлено предыдущее, не Checking
    }

    @Test
    fun downloadFailureThenRetrySucceeds() = runTest {
        val applier = FakeUpdateApplier(downloadFailure = RuntimeException("net down"))
        val channel = FakeUpdateChannel(available())
        val manager = manager(channel, applier)
        manager.checkForUpdates()

        assertTrue(manager.downloadUpdate().isFailure)
        val error = assertIs<UpdateState.Error>(manager.state.value)
        assertEquals("net down", error.message)
        assertIs<UpdateState.Available>(error.previousState)

        assertTrue(applier.applied.isEmpty())

        applier.downloadFailure = null
        assertTrue(manager.downloadUpdate().isSuccess)
        assertIs<UpdateState.ReadyToInstall>(manager.state.value)
        assertEquals(2, applier.downloadCalls)
        assertEquals(1, channel.calls)
    }

    @Test
    fun downloadAndApplyFromWrongStateFailWithoutChangingState() = runTest {
        val applier = FakeUpdateApplier()
        val manager = manager(FakeUpdateChannel(available()), applier)

        assertTrue(manager.downloadUpdate().isFailure)
        assertEquals(UpdateState.Idle(), manager.state.value)
        assertIs<UpdateApplyResult.Failed>(manager.applyUpdate())
        assertEquals(UpdateState.Idle(), manager.state.value)

        manager.checkForUpdates()
        assertIs<UpdateApplyResult.Failed>(manager.applyUpdate())
        assertIs<UpdateState.Available>(manager.state.value)
        assertEquals(0, applier.downloadCalls)
        assertTrue(applier.applied.isEmpty())
    }

    @Test
    fun downloadWithoutMatchedAssetFails() = runTest {
        val applier = FakeUpdateApplier()
        val manager = manager(FakeUpdateChannel(available(matched = null)), applier)
        manager.checkForUpdates()
        assertTrue(manager.downloadUpdate().isFailure)
        assertIs<UpdateState.Available>(manager.state.value)
        assertEquals(0, applier.downloadCalls)
    }

    @Test
    fun applyFailureGoesToErrorWithReadyPrevious() = runTest {
        val applier = FakeUpdateApplier(applyResult = UpdateApplyResult.Failed("denied"))
        val manager = manager(FakeUpdateChannel(available()), applier)
        manager.checkForUpdates()
        manager.downloadUpdate()

        assertIs<UpdateApplyResult.Failed>(manager.applyUpdate())
        val error = assertIs<UpdateState.Error>(manager.state.value)
        assertEquals("denied", error.message)
        assertIs<UpdateState.ReadyToInstall>(error.previousState)
    }

    @Test
    fun notifyOnlyApplierProducesVirtualAsset() = runTest {
        val applier = FakeUpdateApplier(
            capability = ApplierCapability.NOTIFY_ONLY,
            applyResult = UpdateApplyResult.NotificationDispatched(release.htmlUrl),
        )
        val manager = manager(FakeUpdateChannel(available()), applier)
        manager.checkForUpdates()
        assertEquals(
            ApplierCapability.NOTIFY_ONLY,
            assertIs<UpdateState.Available>(manager.state.value).capability,
        )

        val downloaded = manager.downloadUpdate().getOrThrow()
        assertIs<DownloadedAsset.Virtual>(downloaded)
        val ready = assertIs<UpdateState.ReadyToInstall>(manager.state.value)
        assertEquals(ApplierCapability.NOTIFY_ONLY, ready.capability)

        val result = assertIs<UpdateApplyResult.NotificationDispatched>(manager.applyUpdate())
        assertEquals(release.htmlUrl, result.url)
        assertIs<UpdateState.Idle>(manager.state.value)
    }

    @Test
    fun dismissReturnsToIdleKeepingLastChecked() = runTest {
        val manager = manager(FakeUpdateChannel(available()))
        manager.checkForUpdates()
        manager.dismiss()
        val idle = assertIs<UpdateState.Idle>(manager.state.value)
        assertTrue(idle.lastCheckedAt != null)
        assertEquals(release.version, idle.latestKnownVersion)
    }

    @Test
    fun checkDuringDownloadingDoesNotClobberState() = runTest {
        val gate = CompletableDeferred<Unit>()
        val channel = FakeUpdateChannel(available())
        val manager = manager(channel, FakeUpdateApplier(downloadGate = gate))
        manager.checkForUpdates()

        val job = launch { manager.downloadUpdate() }
        runCurrent()
        assertIs<UpdateState.Downloading>(manager.state.value)

        channel.result = UpdateCheckResult.NoUpdate(current)
        val result = manager.checkForUpdates(force = true)
        assertIs<UpdateCheckResult.UpdateAvailable>(result)
        assertEquals(1, channel.calls)
        assertIs<UpdateState.Downloading>(manager.state.value)

        manager.dismiss() // тоже не прерывает прогон
        assertIs<UpdateState.Downloading>(manager.state.value)

        gate.complete(Unit)
        job.join()
        assertIs<UpdateState.ReadyToInstall>(manager.state.value)
    }

    @Test
    fun dismissFromErrorKeepsNothingBroken() = runTest {
        val manager = manager(FakeUpdateChannel(UpdateCheckResult.Error("x")))
        manager.checkForUpdates()
        manager.dismiss()
        val idle = assertIs<UpdateState.Idle>(manager.state.value)
        assertNull(idle.latestKnownVersion)
    }

    @Test
    fun applyRetryAfterFailureDoesNotRedownload() = runTest {
        val applier = FakeUpdateApplier(applyResult = UpdateApplyResult.Failed("denied"))
        val channel = FakeUpdateChannel(available())
        val manager = manager(channel, applier)
        manager.checkForUpdates()
        manager.downloadUpdate()
        assertIs<UpdateApplyResult.Failed>(manager.applyUpdate())
        assertIs<UpdateState.Error>(manager.state.value)

        applier.applyResult = UpdateApplyResult.LaunchedInstaller("ok")
        assertIs<UpdateApplyResult.LaunchedInstaller>(manager.applyUpdate())
        assertIs<UpdateState.Installing>(manager.state.value)
        assertEquals(1, applier.downloadCalls)
        assertEquals(1, channel.calls)
        assertEquals(2, applier.applied.size)
    }

    @Test
    fun dismissAfterLaunchedInstallerReturnsToIdle() = runTest {
        val manager = manager(FakeUpdateChannel(available()))
        manager.checkForUpdates()
        manager.downloadUpdate()
        manager.applyUpdate()
        assertIs<UpdateState.Installing>(manager.state.value)
        manager.dismiss()
        assertIs<UpdateState.Idle>(manager.state.value)
    }

    @Test
    fun dismissAfterSuccessWithRestartReturnsToIdle() = runTest {
        val applier = FakeUpdateApplier(applyResult = UpdateApplyResult.Success("done", requiresRestart = true))
        val manager = manager(FakeUpdateChannel(available()), applier)
        manager.checkForUpdates()
        manager.downloadUpdate()
        manager.applyUpdate()
        assertIs<UpdateState.Installing>(manager.state.value)
        manager.dismiss()
        assertIs<UpdateState.Idle>(manager.state.value)
    }

    @Test
    fun dismissWhileApplyInFlightIsNoOp() = runTest {
        val gate = CompletableDeferred<Unit>()
        val applier = FakeUpdateApplier(applyGate = gate)
        val manager = manager(FakeUpdateChannel(available()), applier)
        manager.checkForUpdates()
        manager.downloadUpdate()
        val job = launch { manager.applyUpdate() }
        runCurrent()
        assertIs<UpdateState.Installing>(manager.state.value)
        manager.dismiss()
        assertIs<UpdateState.Installing>(manager.state.value)
        gate.complete(Unit)
        job.join()
        assertIs<UpdateState.Installing>(manager.state.value)
        manager.dismiss()
        assertIs<UpdateState.Idle>(manager.state.value)
    }

    @Test
    fun cancellationDuringDownloadRestoresAvailableNotError() = runTest {
        val gate = CompletableDeferred<Unit>()
        val manager = manager(FakeUpdateChannel(available()), FakeUpdateApplier(downloadGate = gate))
        manager.checkForUpdates()
        val job = launch { manager.downloadUpdate() }
        runCurrent()
        assertIs<UpdateState.Downloading>(manager.state.value)
        job.cancel()
        job.join()
        assertIs<UpdateState.Available>(manager.state.value)
    }

    private class SocketTimeout : Exception()

    @Test
    fun downloadFailureIsLoggedWithMessageAndCause() = runTest {
        val failure = IllegalStateException("HTTP 404 for https://example.test/app.msi", RuntimeException("root"))
        val manager = manager(FakeUpdateChannel(available()), FakeUpdateApplier(downloadFailure = failure))
        manager.checkForUpdates()
        manager.downloadUpdate()
        val entry = log.entries.single()
        assertTrue("HTTP 404 for https://example.test/app.msi" in entry.message, entry.message)
        assertSame(failure, entry.cause)
    }

    @Test
    fun downloadFailureWithoutMessageKeepsExceptionType() = runTest {
        val manager = manager(FakeUpdateChannel(available()), FakeUpdateApplier(downloadFailure = SocketTimeout()))
        manager.checkForUpdates()
        manager.downloadUpdate()
        val error = assertIs<UpdateState.Error>(manager.state.value)
        assertTrue("SocketTimeout" in error.message, error.message)
        assertTrue("SocketTimeout" in log.entries.single().message)
    }

    @Test
    fun applyFailureIsLoggedWithCause() = runTest {
        val cause = RuntimeException("msiexec exit 1603")
        val applier = FakeUpdateApplier(applyResult = UpdateApplyResult.Failed("Installer failed", cause))
        val manager = manager(FakeUpdateChannel(available()), applier)
        manager.checkForUpdates()
        manager.downloadUpdate()
        manager.applyUpdate()
        val entry = log.entries.single()
        assertTrue("Installer failed" in entry.message, entry.message)
        assertSame(cause, entry.cause)
    }

    @Test
    fun successfulCycleLogsNothing() = runTest {
        val manager = manager(FakeUpdateChannel(available()))
        manager.checkForUpdates()
        manager.downloadUpdate()
        manager.applyUpdate()
        assertTrue(log.entries.isEmpty())
    }
}
