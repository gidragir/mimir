package dev.mimir.core

import kotlin.math.roundToLong

data class RequestSample(
    val op: String,
    val isWrite: Boolean,
    val durationMs: Long,
    val status: Int?,
    val reason: String?,
    val attempt: Int,
    val waitedBeforeMs: Long,
    val networkError: Boolean,
)

data class OpStats(
    val count: Int,
    val p50Ms: Long,
    val p95Ms: Long,
)

data class RunStats(
    val durationMs: Long,
    val requests: Int,
    val writes: Int,
    val writesPerSecond: Double,
    val requestsPerSecond: Double,
    val byOp: Map<String, OpStats>,
    val rateLimitedUser: Int,
    val rateLimitedProject: Int,
    val tooManyRequests: Int,
    val serverErrors: Int,
    val networkErrors: Int,
    val retrySleepMs: Long,
    val lockWaitMs: Map<String, Long>,
    val outcome: String,
)

interface SyncMetrics {
    suspend fun onRequest(sample: RequestSample) {}
    suspend fun onLockWait(name: String, waitedMs: Long) {}
    suspend fun runStarted() {}
    suspend fun runFinished(outcome: String): RunStats? = null

    companion object {
        val None: SyncMetrics = object : SyncMetrics {}
    }
}

fun RunStats.toLogLine(): String {
    val durSec = formatFixed2(durationMs / 1000.0)
    val wps = formatFixed2(writesPerSecond)
    val header = "sync-run outcome=$outcome dur=${durSec}s req=$requests writes=$writes wps=$wps " +
        "403u=$rateLimitedUser 403p=$rateLimitedProject 429=$tooManyRequests " +
        "5xx=$serverErrors net=$networkErrors sleep=$retrySleepMs"
    if (byOp.isEmpty()) return header
    val ops = byOp.entries
        .sortedBy { it.key }
        .joinToString(" ") { (op, st) -> "$op=${st.count}/p50=${st.p50Ms}/p95=${st.p95Ms}" }
    return "$header $ops"
}

private fun formatFixed2(value: Double): String {
    val hundredths = (value * 100.0).roundToLong()
    val sign = if (hundredths < 0) "-" else ""
    val abs = if (hundredths < 0) -hundredths else hundredths
    val whole = abs / 100
    val frac = abs % 100
    val fracStr = if (frac < 10) "0$frac" else "$frac"
    return "$sign$whole.$fracStr"
}
