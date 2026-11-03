package dev.mimir.runtime.update

import dev.mimir.client.UpdateApplyDto
import dev.mimir.client.UpdateCommandException
import dev.mimir.client.UpdateErrorCode
import dev.mimir.client.applyBlocker
import dev.mimir.client.classifyUpdateError
import dev.mimir.client.downloadBlocker
import dev.mimir.client.toDto
import dev.mimir.client.UpdateStatusDto
import dev.mimir.client.disabledUpdateStatus
import dev.mimir.core.BuildConfig
import dev.mimir.core.update.UpdateApplyResult
import dev.mimir.core.update.UpdateCheckResult
import dev.mimir.core.update.UpdateManager
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

    private fun requireEnabled() {
        if (!enabled) throw UpdateCommandException(UpdateErrorCode.UPDATES_DISABLED)
    }

    fun get(): UpdateStatusDto =
        if (enabled) manager.state.value.toDto(currentVersion) else disabledUpdateStatus(currentVersion)

    suspend fun check(): UpdateStatusDto {
        requireEnabled()
        val result = manager.checkForUpdates(force = true)
        if (result is UpdateCheckResult.Error) {
            throw UpdateCommandException(classifyUpdateError(result.message, result.retryAfterSeconds), result.message)
        }
        return get()
    }

    fun download() {
        requireEnabled()
        manager.state.value.downloadBlocker()?.let { throw UpdateCommandException(it) }
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
        requireEnabled()
        manager.state.value.applyBlocker(isSyncing())?.let { throw UpdateCommandException(it) }
        val result = manager.applyUpdate()
        return result.toDto() ?: throw UpdateCommandException(UpdateErrorCode.FAILED, (result as UpdateApplyResult.Failed).error)
    }
}
