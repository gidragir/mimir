package dev.mimir.daemon

import dev.mimir.core.ChangeFeedStorage
import dev.mimir.core.SyncEngine
import dev.mimir.core.VaultChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Запускает синхронизации: сразу при старте, по таймеру (с опросом Change Feed, если он есть) и по локальным
 * изменениям. Одновременно идёт не больше одного прогона; запросы, пришедшие во время прогона, схлопываются
 * в один повторный.
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
    private var jobs: List<Job> = emptyList()
    private var changeToken: String? = null

    @Volatile
    var lastSyncTime: Instant? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    fun start() {
        stop()
        jobs = listOf(
            scope.launch {
                while (true) {
                    tick()
                    delay(syncInterval)
                }
            },
            scope.launch { localChanges.collect { requestSync() } },
        )
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    /** Ручной запуск; `false`, если синхронизация уже идёт. Сам прогон выполняется в фоне. */
    @Suppress("RedundantSuspendModifier")
    suspend fun triggerSync(): Boolean {
        if (!syncMutex.tryLock()) return false
        launchRunner()
        return true
    }

    private fun requestSync() {
        rerunRequested.set(true)
        if (syncMutex.tryLock()) launchRunner()
    }

    private fun launchRunner() {
        scope.launch {
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

    private suspend fun runOnce() {
        try {
            syncEngine.syncNow()
            lastSyncTime = clock.now()
            lastError = null
            retryOnNextTick.set(false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e.message ?: e::class.simpleName
            retryOnNextTick.set(true)
        }
    }

    private suspend fun tick() {
        val feed = remoteFeed
        if (feed == null || retryOnNextTick.get()) {
            requestSync()
            return
        }
        try {
            val token = changeToken
            if (token == null) {
                changeToken = feed.getLatestChangeToken()
                requestSync()
            } else {
                val result = feed.getChanges(token)
                changeToken = result.nextToken
                if (result.changes.isNotEmpty()) requestSync()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e.message ?: e::class.simpleName // сеть недоступна: повторим на следующем тике
        }
    }
}
