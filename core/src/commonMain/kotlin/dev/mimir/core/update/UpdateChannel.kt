package dev.mimir.core.update

sealed interface UpdateCheckResult {
    data class NoUpdate(val checkedVersion: SemVer) : UpdateCheckResult

    data class UpdateAvailable(val release: UpdateRelease, val matchedAsset: UpdateAsset?) : UpdateCheckResult

    data class Error(val message: String, val cause: Throwable? = null) : UpdateCheckResult
}

/** Источник релизов (GitHub Releases и др.). */
interface UpdateChannel {
    val name: String

    suspend fun checkForUpdates(currentVersion: SemVer, targetPlatform: TargetPlatform): UpdateCheckResult
}
