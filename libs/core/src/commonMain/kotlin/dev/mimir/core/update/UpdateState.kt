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

    /** [step] — какой шаг не удался: от него зависит, что повторять. */
    data class Error(val message: String, val previousState: UpdateState, val step: UpdateStep = UpdateStep.CHECK) : UpdateState
}

enum class UpdateStep { CHECK, DOWNLOAD, INSTALL }
