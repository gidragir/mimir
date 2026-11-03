package dev.mimir.runtime.update

import dev.mimir.core.BuildConfig
import dev.mimir.core.update.ApplierCapability
import dev.mimir.core.update.DefaultUpdateManager
import dev.mimir.core.update.DownloadedAsset
import dev.mimir.core.update.GitHubReleasesChannel
import dev.mimir.core.update.SemVer
import dev.mimir.core.update.TargetPlatform
import dev.mimir.core.update.UpdateApplier
import dev.mimir.core.update.UpdateApplyResult
import dev.mimir.core.update.UpdateAsset
import dev.mimir.core.update.UpdateCheckResult
import dev.mimir.core.update.UpdateManager
import dev.mimir.core.update.UpdateRelease
import dev.mimir.core.update.UpdateState
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Аппликер по умолчанию: ничего не качает и не ставит (скачивание виртуальное); `apply` лишь возвращает ссылку на
 * ассет в [UpdateApplyResult.NotificationDispatched]. Показом этой ссылки занимается вызывающий (UI платформы).
 * Платформы подставляют свой [UpdateApplier].
 */
object NoopUpdateApplier : UpdateApplier {
    override val capability: ApplierCapability = ApplierCapability.NOTIFY_ONLY

    override suspend fun download(
        asset: UpdateAsset,
        release: UpdateRelease,
        onProgress: (Float, Long, Long) -> Unit,
    ): Result<DownloadedAsset> = Result.success(DownloadedAsset.Virtual(asset))

    override suspend fun apply(downloadedAsset: DownloadedAsset): UpdateApplyResult =
        UpdateApplyResult.NotificationDispatched(downloadedAsset.asset.downloadUrl)
}

/** Менеджер по умолчанию, когда платформа обновления не подключила: проверка сообщает об этом, ничего не делает. */
object DisabledUpdateManager : UpdateManager {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle())
    override val state: StateFlow<UpdateState> = _state

    override suspend fun checkForUpdates(force: Boolean): UpdateCheckResult = UpdateCheckResult.Error("Updates are not configured")

    override suspend fun downloadUpdate(): Result<DownloadedAsset> = Result.failure(IllegalStateException("Updates are not configured"))

    override suspend fun applyUpdate(): UpdateApplyResult = UpdateApplyResult.Failed("Updates are not configured")

    override fun dismiss() = Unit
}

/**
 * Собирает [UpdateManager] поверх GitHub Releases проекта (владелец/репозиторий/версия берутся из `BuildConfig`,
 * то есть из `gradle.properties`). Платформа передаёт свой [applier] и [targetPlatform].
 */
fun createGitHubUpdateManager(
    httpClient: HttpClient,
    targetPlatform: TargetPlatform,
    applier: UpdateApplier = NoopUpdateApplier,
    currentVersion: SemVer = SemVer.parse(BuildConfig.VERSION),
): UpdateManager = DefaultUpdateManager(
    currentVersion = currentVersion,
    targetPlatform = targetPlatform,
    channel = GitHubReleasesChannel(BuildConfig.GITHUB_OWNER, BuildConfig.GITHUB_REPO, KtorUpdateHttpClient(httpClient)),
    applier = applier,
)
