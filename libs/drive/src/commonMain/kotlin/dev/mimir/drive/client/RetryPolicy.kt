package dev.mimir.drive.client

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.fromHttpToGmtDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Политика повторов запросов к Drive: [maxAttempts] — общее число запросов (первый + повторы),
 * паузы растут экспоненциально от [baseDelay] до [maxDelay] с «equal jitter».
 */
data class RetryPolicy(
    val maxAttempts: Int = 5,
    val baseDelay: Duration = 1.seconds,
    val maxDelay: Duration = 32.seconds,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
    }

    /**
     * Пауза после неудачной попытки [attempt] (с 1): `d = min(maxDelay, baseDelay * 2^(attempt-1))`,
     * результат — `d/2 + random(0..d/2)`, то есть в `[d/2, d]`.
     */
    fun backoff(attempt: Int, random: Random): Duration {
        val exponent = (attempt - 1).coerceIn(0, MAX_EXPONENT)
        val d = minOf(maxDelay, baseDelay * (1 shl exponent))
        val half = d / 2
        return half + half * random.nextDouble()
    }

    private companion object {
        const val MAX_EXPONENT = 30
    }
}

/**
 * Значение `Retry-After`: целое число секунд или HTTP-дата (RFC 1123) относительно [now].
 * Прошедшая дата → [Duration.ZERO]. Нечитаемое значение → `null` (действует обычный backoff).
 */
internal fun parseRetryAfter(header: String?, now: Instant): Duration? {
    val value = header?.trim().orEmpty()
    if (value.isEmpty()) return null
    if (value.all { it in '0'..'9' }) return value.toLongOrNull()?.seconds
    val dateMillis = try {
        value.fromHttpToGmtDate().timestamp
    } catch (e: Exception) {
        return null
    }
    val wait = (dateMillis - now.toEpochMilliseconds()).milliseconds
    return if (wait.isNegative()) Duration.ZERO else wait
}

private val RATE_LIMIT_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded")

/** Тело 403 Drive — `{"error":{"errors":[{"reason":"rateLimitExceeded"}]}}`: ограничение частоты, а не отказ в правах. */
internal suspend fun isRateLimit403(response: HttpResponse): Boolean {
    val body = try {
        response.bodyAsText()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return false
    }
    return try {
        val errors = (Json.parseToJsonElement(body).jsonObject["error"] as? JsonObject)?.get("errors") as? JsonArray
        errors.orEmpty().any { item ->
            val reason = ((item as? JsonObject)?.get("reason") as? JsonPrimitive)?.contentOrNull
            reason in RATE_LIMIT_REASONS
        }
    } catch (e: Exception) {
        false
    }
}
