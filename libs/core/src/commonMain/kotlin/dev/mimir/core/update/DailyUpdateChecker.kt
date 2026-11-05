package dev.mimir.core.update

import kotlinx.coroutines.CancellationException
import kotlin.time.Clock

/**
 * Ограничитель частоты проверок для клиентов без демона (плагин Obsidian, расширение): [UpdateManager]
 * проверяется не чаще раза в [intervalSeconds]. Время последней успешной проверки хранит платформа
 * через [loadLastChecked]/[saveLastChecked] (эпоха, секунды). Ошибка проверки интервал не расходует;
 * сбои хранилища наружу не выходят.
 */
class DailyUpdateChecker(
    private val manager: UpdateManager,
    private val clock: Clock,
    private val loadLastChecked: suspend () -> Long?,
    private val saveLastChecked: suspend (Long) -> Unit,
    private val intervalSeconds: Long = 24 * 3600L,
) {
    /** Результат проверки или `null`, если срок ещё не вышел. */
    suspend fun checkIfDue(): UpdateCheckResult? {
        val now = clock.now().epochSeconds
        val last = try {
            loadLastChecked()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        if (last != null && now - last in 0 until intervalSeconds) return null
        val result = manager.checkForUpdates()
        if (result !is UpdateCheckResult.Error) {
            try {
                saveLastChecked(now)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
        }
        return result
    }
}
