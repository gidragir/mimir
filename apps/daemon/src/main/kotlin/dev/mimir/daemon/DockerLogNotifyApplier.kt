package dev.mimir.daemon

import dev.mimir.core.update.ApplierCapability
import dev.mimir.core.update.DownloadedAsset
import dev.mimir.core.update.UpdateApplier
import dev.mimir.core.update.UpdateApplyResult
import dev.mimir.core.update.UpdateAsset
import dev.mimir.core.update.UpdateRelease

private val applierLogger = java.util.logging.Logger.getLogger(DockerLogNotifyApplier::class.java.name)

/**
 * Аппликер демона без собственной установки (Docker, headless, спека §4): скачивать нечего
 * ([DownloadedAsset.Virtual]), а `apply` пишет в лог ссылку на релиз и возвращает [UpdateApplyResult.NotificationDispatched].
 * Образ обновляет оператор (`docker pull`). Платформы с установкой (Windows MSI) передают свой [UpdateApplier].
 */
class DockerLogNotifyApplier(
    private val log: (String) -> Unit = applierLogger::info,
) : UpdateApplier {
    override val capability: ApplierCapability = ApplierCapability.NOTIFY_ONLY

    @Volatile
    private var release: UpdateRelease? = null

    override suspend fun download(
        asset: UpdateAsset,
        release: UpdateRelease,
        onProgress: (Float, Long, Long) -> Unit,
    ): Result<DownloadedAsset> {
        this.release = release
        return Result.success(DownloadedAsset.Virtual(asset))
    }

    override suspend fun apply(downloadedAsset: DownloadedAsset): UpdateApplyResult {
        val known = release
        if (known == null) {
            val url = downloadedAsset.asset.downloadUrl
            log("Update available: $url")
            return UpdateApplyResult.NotificationDispatched(url)
        }
        log("Update v${known.version} available: ${known.htmlUrl}")
        return UpdateApplyResult.NotificationDispatched(known.htmlUrl)
    }
}
