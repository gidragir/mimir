package dev.mimir.extension.update

import dev.mimir.core.update.DailyUpdateChecker
import dev.mimir.core.update.SemVer
import dev.mimir.core.update.UpdateCheckResult
import dev.mimir.core.update.UpdateManager
import dev.mimir.core.update.UpdateState
import dev.mimir.extension.platform.ExtensionPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Clock

/**
 * Периодическая проверка обновлений расширения. Сервис-воркер MV3 не загружает Kotlin-код и умирает между
 * событиями, поэтому проверка идёт при открытии панели/popup и не чаще раза в 24 часа (время последней
 * проверки и известная новая версия лежат в `chrome.storage.local`, это не секреты).
 */
class ExtensionUpdateController(
    private val manager: UpdateManager,
    private val platform: ExtensionPlatform,
    private val currentVersion: SemVer,
    private val clock: Clock = Clock.System,
) {
    val state: StateFlow<UpdateState> get() = manager.state

    private val daily = DailyUpdateChecker(
        manager,
        clock,
        loadLastChecked = { platform.getLocalString(KEY_LAST_CHECKED)?.toLongOrNull() },
        saveLastChecked = { platform.setLocalString(KEY_LAST_CHECKED, it.toString()) },
    )

    /** Проверяет обновления, если с прошлой проверки прошло не меньше суток; ошибки наружу не бросает. */
    suspend fun checkIfDue() {
        try {
            when (val result = daily.checkIfDue()) {
                null -> restoreBadgeFromStoredVersion()
                is UpdateCheckResult.UpdateAvailable -> platform.setLocalString(KEY_AVAILABLE, result.release.version.toString())
                is UpdateCheckResult.NoUpdate -> platform.removeLocalKey(KEY_AVAILABLE)
                is UpdateCheckResult.Error -> Unit // повторим при следующем открытии
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // проверка обновлений никогда не ломает панель
        }
    }

    /** «Установка» для расширения — открытие страницы релиза. */
    suspend fun openReleasePage() {
        try {
            if (manager.state.value is UpdateState.Available) manager.downloadUpdate()
            manager.applyUpdate()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }

    fun dismiss() = manager.dismiss()

    private suspend fun restoreBadgeFromStoredVersion() {
        val known = platform.getLocalString(KEY_AVAILABLE)?.let { runCatching { SemVer.parse(it) }.getOrNull() }
        if (known == null || !known.isNewerThan(currentVersion)) platform.setBadge("")
    }

    private companion object {
        const val KEY_LAST_CHECKED = "update.lastCheckedAt"
        const val KEY_AVAILABLE = "update.availableVersion"
    }
}
