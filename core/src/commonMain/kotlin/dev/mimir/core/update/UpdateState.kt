package dev.mimir.core.update

import kotlin.time.Instant

sealed interface UpdateState {
    data class Idle(
        val lastCheckedAt: Instant? = null,
        val latestKnownVersion: SemVer? = null,
    ) : UpdateState

    data object Checking : UpdateState

    data class Available(
        val release: UpdateRelease,
        val asset: UpdateAsset?,
        val capability: ApplierCapability,
    ) : UpdateState

    data class Downloading(
        val release: UpdateRelease,
        val asset: UpdateAsset,
        val progress: Float,
        val bytesRead: Long,
        val totalBytes: Long,
    ) : UpdateState

    data class ReadyToInstall(
        val release: UpdateRelease,
        val downloadedAsset: DownloadedAsset,
        val capability: ApplierCapability,
    ) : UpdateState

    data class Installing(val release: UpdateRelease) : UpdateState

    data class Error(val message: String, val previousState: UpdateState) : UpdateState
}
