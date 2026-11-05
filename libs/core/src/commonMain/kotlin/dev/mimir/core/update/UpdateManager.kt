package dev.mimir.core.update

import kotlinx.coroutines.flow.StateFlow

/** Оркестратор обновления: проверка, скачивание, применение. */
interface UpdateManager {
    val state: StateFlow<UpdateState>

    suspend fun checkForUpdates(force: Boolean = false): UpdateCheckResult

    suspend fun downloadUpdate(): Result<DownloadedAsset>

    suspend fun applyUpdate(): UpdateApplyResult

    fun dismiss()
}
