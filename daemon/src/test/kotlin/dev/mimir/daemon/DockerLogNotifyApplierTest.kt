package dev.mimir.daemon

import dev.mimir.core.update.ApplierCapability
import dev.mimir.core.update.DownloadedAsset
import dev.mimir.core.update.UpdateApplyResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DockerLogNotifyApplierTest {
    private val logged = mutableListOf<String>()
    private val applier = DockerLogNotifyApplier(log = { logged += it })

    @Test
    fun `is notify-only`() {
        assertEquals(ApplierCapability.NOTIFY_ONLY, applier.capability)
    }

    @Test
    fun `download is virtual and reports no progress needs`() = runTest {
        val result = applier.download(testAsset, testRelease) { _, _, _ -> }

        assertEquals(DownloadedAsset.Virtual(testAsset), result.getOrThrow())
        assertEquals(emptyList(), logged)
    }

    @Test
    fun `apply logs the version and release url and returns the notification`() = runTest {
        val downloaded = applier.download(testAsset, testRelease) { _, _, _ -> }.getOrThrow()

        val result = applier.apply(downloaded)

        assertEquals(listOf("Update v1.2.3 available: https://example.test/releases/v1.2.3"), logged)
        assertEquals(UpdateApplyResult.NotificationDispatched("https://example.test/releases/v1.2.3"), result)
    }

    @Test
    fun `apply without a prior download falls back to the asset url`() = runTest {
        val result = applier.apply(DownloadedAsset.Virtual(testAsset))

        assertIs<UpdateApplyResult.NotificationDispatched>(result)
        assertEquals(testAsset.downloadUrl, result.url)
        assertEquals(listOf("Update available: ${testAsset.downloadUrl}"), logged)
    }
}
