package dev.mimir.core.update

import kotlinx.coroutines.CompletableDeferred

/**
 * Фейк [UpdateApplier]: эмитит прогресс, может падать при скачивании, ждать [downloadGate]
 * и возвращать настроенный результат применения.
 */
class FakeUpdateApplier(
    override val capability: ApplierCapability = ApplierCapability.DOWNLOAD_AND_LAUNCH,
    var progressSteps: List<Triple<Float, Long, Long>> = emptyList(),
    var downloadFailure: Throwable? = null,
    var applyResult: UpdateApplyResult = UpdateApplyResult.LaunchedInstaller("launched"),
    /** Если задан, `download` ждёт его завершения после эмиссии прогресса. */
    var downloadGate: CompletableDeferred<Unit>? = null,
) : UpdateApplier {
    var downloadCalls: Int = 0
        private set
    val applied = mutableListOf<DownloadedAsset>()

    override suspend fun download(
        asset: UpdateAsset,
        release: UpdateRelease,
        onProgress: (Float, Long, Long) -> Unit,
    ): Result<DownloadedAsset> {
        downloadCalls++
        progressSteps.forEach { (p, read, total) -> onProgress(p, read, total) }
        downloadGate?.await()
        downloadFailure?.let { return Result.failure(it) }
        return Result.success(
            if (capability == ApplierCapability.NOTIFY_ONLY) {
                DownloadedAsset.Virtual(asset)
            } else {
                DownloadedAsset.LocalFile("/tmp/${asset.name}", asset.expectedSha256 ?: "sha", asset)
            },
        )
    }

    override suspend fun apply(downloadedAsset: DownloadedAsset): UpdateApplyResult {
        applied += downloadedAsset
        return applyResult
    }
}
