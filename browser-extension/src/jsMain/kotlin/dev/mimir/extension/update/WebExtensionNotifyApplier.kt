package dev.mimir.extension.update

import dev.mimir.core.update.ApplierCapability
import dev.mimir.core.update.DownloadedAsset
import dev.mimir.core.update.UpdateApplier
import dev.mimir.core.update.UpdateApplyResult
import dev.mimir.core.update.UpdateAsset
import dev.mimir.core.update.UpdateRelease
import dev.mimir.core.update.UpdateState
import dev.mimir.extension.platform.ExtensionPlatform
import kotlinx.coroutines.flow.Flow

/**
 * Расширение Chromium нельзя обновить из самого себя (MV3 запрещает удалённый код), поэтому «установка» —
 * открыть страницу релиза во вкладке. Скачивания нет: [download] виртуальный и не делает сетевых запросов.
 */
class WebExtensionNotifyApplier(private val platform: ExtensionPlatform) : UpdateApplier {
    override val capability = ApplierCapability.NOTIFY_ONLY

    private var releasePage: String? = null

    override suspend fun download(
        asset: UpdateAsset,
        release: UpdateRelease,
        onProgress: (Float, Long, Long) -> Unit,
    ): Result<DownloadedAsset> {
        releasePage = release.htmlUrl
        return Result.success(DownloadedAsset.Virtual(asset))
    }

    override suspend fun apply(downloadedAsset: DownloadedAsset): UpdateApplyResult {
        val url = releasePage?.takeIf { it.startsWith("https://") } ?: downloadedAsset.asset.downloadUrl
        if (!url.startsWith("https://")) return UpdateApplyResult.Failed("Не удалось открыть страницу релиза: недопустимый адрес")
        return try {
            platform.openExternalUrl(url)
            UpdateApplyResult.NotificationDispatched(url)
        } catch (e: Exception) {
            UpdateApplyResult.Failed("Не удалось открыть страницу релиза: $url", e)
        }
    }
}

/**
 * Держит значок на иконке расширения в согласии с [states]: «1» при доступном обновлении, пусто после
 * перехода из Checking/Available в Idle. Начальный Idle значок не трогает (он мог быть выставлен прошлой панелью).
 */
suspend fun bindUpdateBadge(states: Flow<UpdateState>, platform: ExtensionPlatform) {
    var seenActive = false
    states.collect { state ->
        when (state) {
            is UpdateState.Available -> {
                seenActive = true
                platform.setBadge("1")
            }
            is UpdateState.Checking -> seenActive = true
            is UpdateState.Idle -> if (seenActive) {
                seenActive = false
                platform.setBadge("")
            }
            else -> Unit
        }
    }
}
