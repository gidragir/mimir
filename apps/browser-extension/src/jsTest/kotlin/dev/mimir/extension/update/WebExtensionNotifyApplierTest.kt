package dev.mimir.extension.update

import dev.mimir.core.update.ApplierCapability
import dev.mimir.core.update.AssetKind
import dev.mimir.core.update.DefaultUpdateManager
import dev.mimir.core.update.DownloadedAsset
import dev.mimir.core.update.SemVer
import dev.mimir.core.update.TargetPlatform
import dev.mimir.core.update.UpdateApplyResult
import dev.mimir.core.update.UpdateAsset
import dev.mimir.core.update.UpdateChannel
import dev.mimir.core.update.UpdateCheckResult
import dev.mimir.core.update.UpdateRelease
import dev.mimir.extension.platform.FakeExtensionPlatform
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class WebExtensionNotifyApplierTest {
    private val page = "https://github.com/gidragir/mimir/releases/tag/v1.1.0"
    private val asset = UpdateAsset("release", page, 0, null, TargetPlatform.BROWSER_EXTENSION_ANY, AssetKind.GENERIC)
    private val release = UpdateRelease(SemVer.parse("1.1.0"), "", Instant.fromEpochSeconds(0), page, listOf(asset))

    private class OneShotChannel(val release: UpdateRelease?, val asset: UpdateAsset?) : UpdateChannel {
        override val name = "fake"
        override suspend fun checkForUpdates(currentVersion: SemVer, targetPlatform: TargetPlatform): UpdateCheckResult =
            if (release == null) UpdateCheckResult.NoUpdate(currentVersion) else UpdateCheckResult.UpdateAvailable(release, asset)
    }

    @Test
    fun downloadIsVirtualAndApplyOpensReleasePage() = runTest {
        val platform = FakeExtensionPlatform()
        val applier = WebExtensionNotifyApplier(platform)
        assertEquals(ApplierCapability.NOTIFY_ONLY, applier.capability)
        val downloaded = applier.download(asset, release) { _, _, _ -> }.getOrThrow()
        assertIs<DownloadedAsset.Virtual>(downloaded)
        val result = applier.apply(downloaded)
        assertEquals(UpdateApplyResult.NotificationDispatched(page), result)
        assertEquals(listOf(page), platform.openedExternalUrls)
    }

    @Test
    fun applyPrefersReleasePageOverAssetDownloadUrl() = runTest {
        val platform = FakeExtensionPlatform()
        val zip = asset.copy(downloadUrl = "https://github.com/x/y/releases/download/v1/ext.zip", kind = AssetKind.EXTENSION_ZIP)
        val applier = WebExtensionNotifyApplier(platform)
        val downloaded = applier.download(zip, release) { _, _, _ -> }.getOrThrow()
        applier.apply(downloaded)
        assertEquals(listOf(page), platform.openedExternalUrls)
    }

    @Test
    fun nonHttpsUrlIsRefused() = runTest {
        val platform = FakeExtensionPlatform()
        val applier = WebExtensionNotifyApplier(platform)
        val bad = asset.copy(downloadUrl = "javascript:alert(1)")
        val result = applier.apply(DownloadedAsset.Virtual(bad))
        assertIs<UpdateApplyResult.Failed>(result)
        assertTrue(platform.openedExternalUrls.isEmpty())
    }

    @Test
    fun badgeShownOnAvailableAndClearedOnDismiss() = runTest(UnconfinedTestDispatcher()) {
        val platform = FakeExtensionPlatform()
        val manager = DefaultUpdateManager(
            SemVer.parse("1.0.0"), TargetPlatform.BROWSER_EXTENSION_ANY,
            OneShotChannel(release, asset), WebExtensionNotifyApplier(platform),
        )
        val job = launch(start = CoroutineStart.UNDISPATCHED) { bindUpdateBadge(manager.state, platform) }
        manager.checkForUpdates()
        assertEquals("1", platform.badgeText)
        manager.dismiss()
        assertEquals("", platform.badgeText)
        job.cancel()
    }

    @Test
    fun initialIdleDoesNotClearExistingBadge() = runTest(UnconfinedTestDispatcher()) {
        val platform = FakeExtensionPlatform()
        platform.setBadge("1")
        val manager = DefaultUpdateManager(
            SemVer.parse("1.0.0"), TargetPlatform.BROWSER_EXTENSION_ANY,
            OneShotChannel(null, null), WebExtensionNotifyApplier(platform),
        )
        val job = launch(start = CoroutineStart.UNDISPATCHED) { bindUpdateBadge(manager.state, platform) }
        assertEquals("1", platform.badgeText)
        manager.checkForUpdates() // нет обновления: Checking -> Idle снимает значок
        assertEquals("", platform.badgeText)
        job.cancel()
    }
}
