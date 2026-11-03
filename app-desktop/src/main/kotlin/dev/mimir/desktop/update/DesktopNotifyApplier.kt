package dev.mimir.desktop.update

import dev.mimir.core.update.ApplierCapability
import dev.mimir.core.update.DownloadedAsset
import dev.mimir.core.update.UpdateApplier
import dev.mimir.core.update.UpdateApplyResult
import dev.mimir.core.update.UpdateAsset
import dev.mimir.core.update.UpdateRelease

/**
 * Платформы без автоустановки (Linux, Windows ARM64, неизвестная): открывает страницу релиза в браузере.
 * Ассет-заглушка (`downloadUrl` = страница релиза) никогда не скачивается.
 */
class DesktopNotifyApplier(private val browserOpener: (String) -> Unit) : UpdateApplier {
    override val capability = ApplierCapability.NOTIFY_ONLY

    override suspend fun download(
        asset: UpdateAsset,
        release: UpdateRelease,
        onProgress: (Float, Long, Long) -> Unit,
    ): Result<DownloadedAsset> = Result.success(DownloadedAsset.Virtual(asset))

    override suspend fun apply(downloadedAsset: DownloadedAsset): UpdateApplyResult {
        val url = downloadedAsset.asset.downloadUrl
        return try {
            browserOpener(url)
            UpdateApplyResult.NotificationDispatched(url)
        } catch (e: Exception) {
            UpdateApplyResult.Failed("Could not open the release page: $url", e)
        }
    }
}
