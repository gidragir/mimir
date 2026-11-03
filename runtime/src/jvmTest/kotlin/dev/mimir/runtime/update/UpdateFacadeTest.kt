package dev.mimir.runtime.update

import dev.mimir.client.UpdateStatus
import dev.mimir.core.update.ApplierCapability
import dev.mimir.core.update.AssetKind
import dev.mimir.core.update.DownloadedAsset
import dev.mimir.core.update.SemVer
import dev.mimir.core.update.TargetPlatform
import dev.mimir.core.update.UpdateApplyResult
import dev.mimir.core.update.UpdateAsset
import dev.mimir.core.update.UpdateCheckResult
import dev.mimir.core.update.UpdateManager
import dev.mimir.core.update.UpdateRelease
import dev.mimir.core.update.UpdateState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class UpdateFacadeTest {
    private val asset = UpdateAsset("m.msi", "https://x/m.msi", 10, null, TargetPlatform.WINDOWS_X64, AssetKind.MSI)
    private val release = UpdateRelease(SemVer.parse("0.2.0"), "notes", Instant.fromEpochSeconds(0), "https://x/r", listOf(asset))

    private inner class Manager(initial: UpdateState) : UpdateManager {
        override val state = MutableStateFlow(initial)
        val gate = CompletableDeferred<Unit>()
        var applyResult: UpdateApplyResult = UpdateApplyResult.LaunchedInstaller("ok")
        var applied = 0
        var checkResult: UpdateCheckResult = UpdateCheckResult.NoUpdate(SemVer.parse("0.1.0"))

        override suspend fun checkForUpdates(force: Boolean) = checkResult

        override suspend fun downloadUpdate(): Result<DownloadedAsset> {
            val current = state.value as UpdateState.Available
            state.value = UpdateState.Downloading(current.release, asset, 0.5f, 5, 10)
            gate.await()
            state.value = UpdateState.ReadyToInstall(current.release, DownloadedAsset.Virtual(asset), current.capability)
            return Result.success(DownloadedAsset.Virtual(asset))
        }

        override suspend fun applyUpdate(): UpdateApplyResult {
            applied++
            return applyResult
        }

        override fun dismiss() = Unit
    }

    private fun available() = UpdateState.Available(release, asset, ApplierCapability.DOWNLOAD_AND_LAUNCH)

    @Test
    fun `without a real manager the status is disabled and commands are refused`() = runTest {
        val facade = UpdateFacade(DisabledUpdateManager, backgroundScope, { false }, "0.1.0")
        assertEquals(UpdateStatus.DISABLED, facade.get().status)
        assertFailsWith<IllegalStateException> { facade.check() }
        assertFailsWith<IllegalStateException> { facade.download() }
        assertFailsWith<IllegalStateException> { facade.apply() }
    }

    @Test
    fun `status maps the manager state`() = runTest {
        val manager = Manager(available())
        val facade = UpdateFacade(manager, backgroundScope, { false }, "0.1.0")
        val dto = facade.get()
        assertEquals(UpdateStatus.AVAILABLE, dto.status)
        assertEquals("0.2.0", dto.latestVersion)
        assertEquals("DOWNLOAD_AND_LAUNCH", dto.capability)
    }

    @Test
    fun `check failure surfaces as an error`() = runTest {
        val manager = Manager(UpdateState.Idle())
        manager.checkResult = UpdateCheckResult.Error("rate limit")
        assertEquals("rate limit", assertFailsWith<IllegalStateException> { UpdateFacade(manager, backgroundScope, { false }, "0.1.0").check() }.message)
    }

    @Test
    fun `download goes to the background and a second one is refused`() = runTest {
        val manager = Manager(available())
        val facade = UpdateFacade(manager, backgroundScope, { false }, "0.1.0")
        facade.download()
        assertEquals(UpdateStatus.DOWNLOADING, facade.get().status)
        assertFailsWith<IllegalStateException> { facade.download() }
        manager.gate.complete(Unit)
        runCurrent()
        assertEquals(UpdateStatus.READY_TO_INSTALL, facade.get().status)
    }

    @Test
    fun `download needs an available update`() = runTest {
        assertFailsWith<IllegalStateException> { UpdateFacade(Manager(UpdateState.Idle()), backgroundScope, { false }, "0.1.0").download() }
    }

    @Test
    fun `apply is refused while syncing and before the download`() = runTest {
        val ready = UpdateState.ReadyToInstall(release, DownloadedAsset.Virtual(asset), ApplierCapability.DOWNLOAD_AND_LAUNCH)
        val manager = Manager(ready)
        var syncing = true
        val facade = UpdateFacade(manager, backgroundScope, { syncing }, "0.1.0")
        assertTrue(assertFailsWith<IllegalStateException> { facade.apply() }.message!!.contains("sync"))
        assertEquals(0, manager.applied)
        syncing = false
        assertEquals("installer_launched", facade.apply().result)
        assertEquals(1, manager.applied)
        assertFailsWith<IllegalStateException> { UpdateFacade(Manager(available()), backgroundScope, { false }, "0.1.0").apply() }
    }

    @Test
    fun `apply failure becomes an error and notification keeps the url`() = runTest {
        val ready = UpdateState.ReadyToInstall(release, DownloadedAsset.Virtual(asset), ApplierCapability.NOTIFY_ONLY)
        val manager = Manager(ready)
        val facade = UpdateFacade(manager, backgroundScope, { false }, "0.1.0")
        manager.applyResult = UpdateApplyResult.NotificationDispatched("https://x/r")
        assertEquals("https://x/r", facade.apply().url)
        manager.applyResult = UpdateApplyResult.Failed("boom")
        assertEquals("boom", assertFailsWith<IllegalStateException> { facade.apply() }.message)
    }
}
