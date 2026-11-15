package dev.mimir.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ceil
import kotlin.time.Clock
import kotlin.time.Instant

/** Агрегатор метрик одного прогона синхронизации. */
class RecordingSyncMetrics(
    private val clock: Clock = Clock.System,
) : SyncMetrics {
    private val mutex = Mutex()
    private var startedAt: Instant? = null
    private val samples = mutableListOf<RequestSample>()
    private val lockWaits = mutableMapOf<String, Long>()

    override suspend fun runStarted() {
        mutex.withLock {
            startedAt = clock.now()
            samples.clear()
            lockWaits.clear()
        }
    }

    override suspend fun onRequest(sample: RequestSample) {
        mutex.withLock {
            if (startedAt != null) {
                samples += sample
            }
        }
    }

    override suspend fun onLockWait(name: String, waitedMs: Long) {
        mutex.withLock {
            if (startedAt != null) {
                lockWaits[name] = (lockWaits[name] ?: 0L) + waitedMs
            }
        }
    }

    override suspend fun runFinished(outcome: String): RunStats? = mutex.withLock {
        val started = startedAt ?: return@withLock null
        val durationMs = (clock.now() - started).inWholeMilliseconds.coerceAtLeast(0L)
        val snapshot = samples.toList()
        val locks = lockWaits.toMap()

        startedAt = null
        samples.clear()
        lockWaits.clear()

        val requests = snapshot.size
        val writes = snapshot.count { it.isWrite }
        val durationSec = durationMs / 1000.0
        val writesPerSecond = if (durationSec > 0.0) writes / durationSec else 0.0
        val requestsPerSecond = if (durationSec > 0.0) requests / durationSec else 0.0

        val byOp = snapshot.groupBy { it.op }.mapValues { (_, group) ->
            val sorted = group.map { it.durationMs }.sorted()
            OpStats(
                count = sorted.size,
                p50Ms = percentile(sorted, 0.50),
                p95Ms = percentile(sorted, 0.95),
            )
        }

        RunStats(
            durationMs = durationMs,
            requests = requests,
            writes = writes,
            writesPerSecond = writesPerSecond,
            requestsPerSecond = requestsPerSecond,
            byOp = byOp,
            rateLimitedUser = snapshot.count { it.status == 403 && it.reason == "userRateLimitExceeded" },
            rateLimitedProject = snapshot.count { it.status == 403 && it.reason == "rateLimitExceeded" },
            tooManyRequests = snapshot.count { it.status == 429 },
            serverErrors = snapshot.count { it.status != null && it.status in 500..599 },
            networkErrors = snapshot.count { it.networkError },
            retrySleepMs = snapshot.sumOf { it.waitedBeforeMs },
            lockWaitMs = locks,
            outcome = outcome,
        )
    }

    private fun percentile(sorted: List<Long>, p: Double): Long {
        if (sorted.isEmpty()) return 0L
        val idx = (ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }
}
