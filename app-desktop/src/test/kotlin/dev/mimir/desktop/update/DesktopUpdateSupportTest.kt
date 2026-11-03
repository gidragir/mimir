package dev.mimir.desktop.update

import dev.mimir.core.update.ApplierCapability
import dev.mimir.core.update.AssetKind
import dev.mimir.core.update.DownloadedAsset
import dev.mimir.core.update.SemVer
import dev.mimir.core.update.TargetPlatform
import dev.mimir.core.update.UpdateApplyResult
import dev.mimir.core.update.UpdateAsset
import dev.mimir.core.update.UpdateRelease
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class DesktopUpdateSupportTest {
    private val placeholder = UpdateAsset("release page", "https://github.com/o/r/releases/tag/v1", 0, null, TargetPlatform.LINUX_X64, AssetKind.GENERIC)
    private val release = UpdateRelease(SemVer.parse("1.0.0"), "", Instant.fromEpochSeconds(0), "https://github.com/o/r/releases/tag/v1", emptyList())

    @Test
    fun `platform detection table`() {
        val table = listOf(
            Triple("Windows 11", "amd64", TargetPlatform.WINDOWS_X64),
            Triple("Windows 10", "x86_64", TargetPlatform.WINDOWS_X64),
            Triple("Windows 11", "aarch64", TargetPlatform.WINDOWS_ARM64),
            Triple("Windows 11", "arm64", TargetPlatform.WINDOWS_ARM64),
            Triple("Linux", "amd64", TargetPlatform.LINUX_X64),
            Triple("Linux", "aarch64", TargetPlatform.LINUX_ARM64),
            Triple("Linux", "riscv64", TargetPlatform.UNKNOWN),
            Triple("Mac OS X", "aarch64", TargetPlatform.UNKNOWN),
            Triple("Windows 11", "x86", TargetPlatform.UNKNOWN),
        )
        table.forEach { (os, arch, expected) -> assertEquals(expected, detectTargetPlatform(os, arch), "$os/$arch") }
    }

    @Test
    fun `factory picks the MSI applier only for Windows x64`() {
        val tmp = Files.createTempDirectory("mimir-fac")
        fun pick(p: TargetPlatform) = DesktopApplierFactory.create(p, { FakeUpdateHttpClient() }, tmp, {}, {})
        assertIs<WindowsMsiApplier>(pick(TargetPlatform.WINDOWS_X64))
        TargetPlatform.entries.filter { it != TargetPlatform.WINDOWS_X64 }.forEach {
            val a = pick(it)
            assertIs<DesktopNotifyApplier>(a, "$it")
            assertEquals(ApplierCapability.NOTIFY_ONLY, a.capability)
        }
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun `notify applier never downloads and opens the release page`() = runBlocking<Unit> {
        val opened = mutableListOf<String>()
        val a = DesktopNotifyApplier { opened += it }
        val dl = a.download(placeholder, release) { _, _, _ -> }.getOrThrow()
        assertIs<DownloadedAsset.Virtual>(dl)
        assertTrue(opened.isEmpty())
        val result = a.apply(dl)
        assertEquals(UpdateApplyResult.NotificationDispatched(placeholder.downloadUrl), result)
        assertEquals(listOf(placeholder.downloadUrl), opened)
    }

    @Test
    fun `notify applier reports a human message when the browser cannot be opened`() = runBlocking<Unit> {
        val a = DesktopNotifyApplier { throw java.io.IOException("no browser") }
        val result = a.apply(DownloadedAsset.Virtual(placeholder))
        val failed = assertIs<UpdateApplyResult.Failed>(result)
        assertTrue(failed.error.contains("release page"))
    }
}
