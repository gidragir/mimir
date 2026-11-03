package dev.mimir.runtime.update

import dev.mimir.client.UpdateApplyDto
import dev.mimir.client.UpdateStatusDto
import dev.mimir.client.disabledUpdateStatus
import dev.mimir.client.toDto
import dev.mimir.core.BuildConfig
import dev.mimir.core.update.UpdateApplyResult
import dev.mimir.core.update.UpdateCheckResult
import dev.mimir.core.update.UpdateManager
import dev.mimir.core.update.UpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * Команды обновления для окна, которое работает с [UpdateManager] напрямую (встроенный демон, Android): те же
 * правила, что у `/api/v1/update` — скачивание уходит в фон, установка не стартует во время синхронизации.
 * Без настоящего менеджера статус — `DISABLED`.
 */
class UpdateFacade(
    private val manager: UpdateManager,
    private val scope: CoroutineScope,
    private val isSyncing: () -> Boolean,
    private val currentVersion: String = BuildConfig.VERSION,
) {
    private val enabled get() = manager !== DisabledUpdateManager

    private fun UpdateState.effective(): UpdateState = if (this is UpdateState.Error) previousState else this

    fun get(): UpdateStatusDto =
        if (enabled) manager.state.value.toDto(currentVersion) else disabledUpdateStatus(currentVersion)

    suspend fun check(): UpdateStatusDto {
        check(enabled) { "Update checks are disabled" }
        val result = manager.checkForUpdates(force = true)
        if (result is UpdateCheckResult.Error) throw IllegalStateException(result.message)
        return get()
    }

    fun download() {
        check(enabled) { "Update checks are disabled" }
        val state = manager.state.value.effective()
        check(state is UpdateState.Available && state.asset != null) { "No update is available to download" }
        // UNDISPATCHED: менеджер успевает перейти в Downloading до возврата, повторный вызов получит отказ.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                manager.downloadUpdate()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Ошибку менеджер уже записал в состояние Error.
            }
        }
    }

    suspend fun apply(): UpdateApplyDto {
        check(enabled) { "Update checks are disabled" }
        check(manager.state.value.effective() is UpdateState.ReadyToInstall) { "No downloaded update to install" }
        check(!isSyncing()) { "A sync run is in progress; try again when it finishes" }
        return when (val result = manager.applyUpdate()) {
            is UpdateApplyResult.Success -> UpdateApplyDto("applied", result.message, result.requiresRestart)
            is UpdateApplyResult.LaunchedInstaller -> UpdateApplyDto("installer_launched", result.message, requiresRestart = true)
            is UpdateApplyResult.NotificationDispatched -> UpdateApplyDto("notification", "Update details: ${result.url}", url = result.url)
            is UpdateApplyResult.Failed -> throw IllegalStateException(result.error)
        }
    }
}
