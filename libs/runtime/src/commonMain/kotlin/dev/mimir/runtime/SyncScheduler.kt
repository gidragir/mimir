package dev.mimir.runtime

import dev.mimir.core.ChangeFeedStorage
import dev.mimir.core.SyncEngine
import dev.mimir.core.VaultChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Запускает синхронизации: сразу при старте, по таймеру (с опросом Change Feed, если он есть) и по локальным
 * изменениям. Одновременно идёт не больше одного прогона; запросы, пришедшие во время прогона, схлопываются
 * в один повторный. [runNow] (Worker на Android) сериализуется с ними тем же мьютексом.
 */
class SyncScheduler(
    val syncEngine: SyncEngine,
    val syncInterval: Duration,
    private val scope: CoroutineScope,
    private val localChanges: Flow<VaultChange> = emptyFlow(),
    private val remoteFeed: ChangeFeedStorage? = null,
    private val clock: Clock = Clock.System,
) {
    private val syncMutex = Mutex()
    private val rerunRequested = AtomicBoolean(false)
    private val retryOnNextTick = AtomicBoolean(false)
    private val syncStartCount = AtomicLong(0)
    private var jobs: List<Job> = emptyList()

    @Volatile
    private var runner: Job? = null
    private var changeToken: String? = null

    /** `true`, если сейчас выполняется фоновый прогон планировщика ([runner]). */
    val isRunning: Boolean get() = runner?.isActive == true

    /** Номер последнего локального события; прогон, начатый после него, снимает [pendingLocalChanges]. */
    private val localEventSeq = AtomicLong(0)
    private val pendingLock = Any()
    private val _pendingLocalChanges = MutableStateFlow(false)

    /** `true` по локальному событию, `false` после успешного прогона, начатого после него. */
    val pendingLocalChanges: StateFlow<Boolean> = _pendingLocalChanges.asStateFlow()

    @Volatile
    var lastSyncTime: Instant? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** Причина остановки отслеживания локальных изменений; синхронизация по таймеру продолжается. */
    @Volatile
    var watcherError: String? = null
        private set

    fun start() {
        stop(cancelRunner = false) // перезапуск не должен обрывать идущий прогон
        jobs = listOf(
            scope.launch {
                while (true) {
                    tick()
                    delay(syncInterval)
                }
            },
            scope.launch {
                // сломанный watcher не должен останавливать синхронизацию по таймеру
                localChanges
                    .catch { watcherError = it.message ?: it::class.simpleName }
                    .collect {
                        synchronized(pendingLock) {
                            localEventSeq.incrementAndGet()
                            _pendingLocalChanges.value = true
                        }
                        requestSync()
                    }
            },
        )
    }

    fun stop(cancelRunner: Boolean = true) {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        if (cancelRunner) {
            val activeRunner = runner
            if (activeRunner?.isActive == true) {
                retryOnNextTick.set(true)
            }
            activeRunner?.cancel() // прогон не должен пережить планировщик: новый мог бы запуститься параллельно
        }
    }

    /** Ручной запуск; `false`, если синхронизация уже идёт. Сам прогон выполняется в фоне. */
    @Suppress("RedundantSuspendModifier")
    suspend fun triggerSync(): Boolean {
        if (!syncMutex.tryLock()) return false
        launchRunner()
        return true
    }

    /**
     * Один прогон до конца в вызывающей корутине: если идёт другой, ждёт его, а не запускается параллельно.
     * Работает и при остановленном планировщике. Ошибка прогона пробрасывается (после записи в [lastError]).
     */
    suspend fun runNow(): Boolean {
        syncMutex.withLock {
            rerunRequested.set(false)
            runOnce(rethrow = true)
        }
        if (rerunRequested.get() && syncMutex.tryLock()) launchRunner()
        return true
    }

    @Volatile
    private var paused = false

    /**
     * Ждёт окончания идущего прогона (не дольше [timeout]) и удерживает мьютекс, пока не вызван [resume]: новые
     * прогоны (таймер, триггеры, [runNow]) не стартуют. `false` — прогон не закончился, ничего не удерживается.
     */
    suspend fun pause(timeout: Duration): Boolean {
        if (paused) return true
        val locked = withTimeoutOrNull(timeout) { syncMutex.lock(); true } ?: false
        if (locked) paused = true
        return locked
    }

    /** Снимает [pause]; запрос, пришедший во время паузы, запускает один повторный прогон. */
    fun resume() {
        if (!paused) return
        paused = false
        syncMutex.unlock()
        if (rerunRequested.get() && syncMutex.tryLock()) launchRunner()
    }

    private fun requestSync() {
        rerunRequested.set(true)
        if (syncMutex.tryLock()) launchRunner()
    }

    private fun launchRunner() {
        runner = scope.launch {
            try {
                do {
                    rerunRequested.set(false)
                    runOnce()
                } while (rerunRequested.get())
            } finally {
                syncMutex.unlock()
            }
            // запрос мог прийти между последней проверкой и unlock
            if (rerunRequested.get() && syncMutex.tryLock()) launchRunner()
        }
    }

    private suspend fun runOnce(rethrow: Boolean = false) {
        syncStartCount.incrementAndGet()
        val startedAfter = localEventSeq.get()
        try {
            syncEngine.syncNow()
            lastSyncTime = clock.now()
            lastError = null
            retryOnNextTick.set(false)
            synchronized(pendingLock) {
                if (localEventSeq.get() == startedAfter) _pendingLocalChanges.value = false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e.message ?: e::class.simpleName
            retryOnNextTick.set(true)
            if (rethrow) throw e
        }
    }

    private suspend fun tick() {
        val feed = remoteFeed
        // без рабочего watcher'а локальные правки видны только полной сверкой
        if (feed == null || retryOnNextTick.get() || watcherError != null || _pendingLocalChanges.value) {
            requestSync()
            return
        }
        try {
            val token = changeToken
            if (token == null) {
                val countBefore = syncStartCount.get()
                changeToken = feed.getLatestChangeToken()
                if (syncStartCount.get() == countBefore) {
                    triggerSync()
                }
            } else {
                val result = feed.getChanges(token)
                changeToken = result.nextToken
                if (result.changes.isNotEmpty()) requestSync()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e.message ?: e::class.simpleName
            // сеть недоступна: изменения могли пройти мимо ленты, на следующем тике нужна полная сверка
            retryOnNextTick.set(true)
        }
    }
}
