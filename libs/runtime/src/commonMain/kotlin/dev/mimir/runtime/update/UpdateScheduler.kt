package dev.mimir.runtime.update

import dev.mimir.core.update.UpdateCheckResult
import dev.mimir.core.update.UpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Фоновая проверка обновлений (спека §6.2): первая через [initialDelay] после старта, далее каждые [interval].
 * Проверка не запускается, пока идёт синхронизация ([isSyncBusy]): тик откладывается на [busyRecheck].
 * Ошибка проверки не роняет цикл; при `retryAfterSeconds` пауза не короче заданной, иначе [errorRetry].
 * Скачивание и установку планировщик не запускает — это явное действие пользователя.
 */
class UpdateScheduler(
    private val manager: UpdateManager,
    private val scope: CoroutineScope,
    private val isSyncBusy: () -> Boolean = { false },
    private val initialDelay: Duration = DEFAULT_INITIAL_DELAY,
    private val interval: Duration = DEFAULT_INTERVAL,
    private val busyRecheck: Duration = DEFAULT_BUSY_RECHECK,
    private val errorRetry: Duration = DEFAULT_ERROR_RETRY,
    /** Настенные часы (не монотонные): срок переживает сон устройства; в тестах — виртуальное время. */
    private val clock: Clock = Clock.System,
) {
    private val lock = Any()
    private var job: Job? = null

    /** Когда сработает следующая проверка; `null` — планировщик ещё не запускали. Переживает stop()/start(). */
    private var nextFireAt: Instant? = null

    /**
     * Идемпотентен и потокобезопасен. После [stop] продолжает прежнее расписание: ждёт остаток до сохранённого срока
     * (не меньше нуля), а не начинает с [initialDelay]; первый запуск использует [initialDelay].
     */
    fun start() {
        synchronized(lock) {
            if (job?.isActive == true) return
            val wait = nextFireAt?.let { (it - clock.now()).coerceAtLeast(Duration.ZERO) } ?: initialDelay
            nextFireAt = clock.now() + wait
            job = scope.launch {
                delay(wait)
                while (true) {
                    val pause = tick()
                    val self = currentCoroutineContext()[Job]
                    synchronized(lock) {
                        if (job === self) nextFireAt = clock.now() + pause
                    }
                    delay(pause)
                }
            }
        }
    }

    /** Останавливает цикл, сохраняя срок следующей проверки для [start]. */
    fun stop() {
        synchronized(lock) {
            job?.cancel()
            job = null
        }
    }

    /** Одна итерация; возвращает паузу до следующей. */
    private suspend fun tick(): Duration {
        if (isSyncBusy()) return busyRecheck
        return try {
            when (val result = manager.checkForUpdates()) {
                is UpdateCheckResult.Error -> result.retryAfterSeconds
                    ?.let { maxOf(it.coerceAtLeast(0).seconds, MIN_PAUSE) }
                    ?: errorRetry
                else -> interval
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            errorRetry
        }
    }

    companion object {
        val DEFAULT_INITIAL_DELAY: Duration = 30.seconds
        val DEFAULT_INTERVAL: Duration = 24.hours
        val DEFAULT_BUSY_RECHECK: Duration = 1.minutes
        val DEFAULT_ERROR_RETRY: Duration = 1.hours
        private val MIN_PAUSE: Duration = 1.seconds
    }
}
