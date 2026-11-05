package dev.mimir.extension.update

import dev.mimir.core.update.AssetKind
import dev.mimir.core.update.DefaultUpdateManager
import dev.mimir.core.update.SemVer
import dev.mimir.core.update.TargetPlatform
import dev.mimir.core.update.UpdateAsset
import dev.mimir.core.update.UpdateChannel
import dev.mimir.core.update.UpdateCheckResult
import dev.mimir.core.update.UpdateRelease
import dev.mimir.core.update.UpdateState
import dev.mimir.extension.platform.FakeExtensionPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Instant

class ExtensionUpdateControllerTest {
    private val page = "https://github.com/gidragir/mimir/releases/tag/v1.1.0"
    private val asset = UpdateAsset("release", page, 0, null, TargetPlatform.BROWSER_EXTENSION_ANY, AssetKind.GENERIC)
    private val release = UpdateRelease(SemVer.parse("1.1.0"), "", Instant.fromEpochSeconds(0), page, listOf(asset))

    private class CountingChannel(val release: UpdateRelease) : UpdateChannel {
        var calls = 0
        override val name = "fake"
        override suspend fun checkForUpdates(currentVersion: SemVer, targetPlatform: TargetPlatform): UpdateCheckResult {
            calls++
            return UpdateCheckResult.UpdateAvailable(release, release.assets.first())
        }
    }

    private fun controller(platform: FakeExtensionPlatform, channel: CountingChannel, now: Instant, current: String = "1.0.0"): ExtensionUpdateController {
        val manager = DefaultUpdateManager(SemVer.parse(current), TargetPlatform.BROWSER_EXTENSION_ANY, channel, WebExtensionNotifyApplier(platform))
        val clock = object : Clock { override fun now() = now }
        return ExtensionUpdateController(manager, platform, SemVer.parse(current), clock)
    }

    @Test
    fun checksAtMostOncePer24Hours() = runTest {
        val platform = FakeExtensionPlatform()
        val channel = CountingChannel(release)
        controller(platform, channel, Instant.fromEpochSeconds(1_000_000)).checkIfDue()
        controller(platform, channel, Instant.fromEpochSeconds(1_000_000 + 3600)).checkIfDue()
        assertEquals(1, channel.calls)
        controller(platform, channel, Instant.fromEpochSeconds(1_000_000 + 25 * 3600)).checkIfDue()
        assertEquals(2, channel.calls)
    }

    @Test
    fun throttledStartKeepsBadgeForKnownNewerVersionAndClearsStaleOne() = runTest {
        val platform = FakeExtensionPlatform()
        val channel = CountingChannel(release)
        controller(platform, channel, Instant.fromEpochSeconds(1_000_000)).checkIfDue()
        platform.setBadge("1")
        // новая панель, проверка не нужна: значок остаётся
        controller(platform, channel, Instant.fromEpochSeconds(1_000_100)).checkIfDue()
        assertEquals("1", platform.badgeText)
        // расширение обновили вручную до 1.1.0: значок снимается
        controller(platform, channel, Instant.fromEpochSeconds(1_000_200), current = "1.1.0").checkIfDue()
        assertEquals("", platform.badgeText)
    }

    @Test
    fun openReleasePageDownloadsVirtuallyAndOpensTab() = runTest {
        val platform = FakeExtensionPlatform()
        val c = controller(platform, CountingChannel(release), Instant.fromEpochSeconds(1_000_000))
        c.checkIfDue()
        assertIs<UpdateState.Available>(c.state.value)
        c.openReleasePage()
        assertEquals(listOf(page), platform.openedExternalUrls)
    }
}
