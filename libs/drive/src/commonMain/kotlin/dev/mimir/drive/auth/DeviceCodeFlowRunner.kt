package dev.mimir.drive.auth

import dev.mimir.drive.api.GoogleDeviceCodeResponse
import dev.mimir.drive.client.DriveAuthException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

enum class DevicePollStep { Pending, SlowDown, Expired, Denied }

fun DriveAuthException.toDevicePollStep(): DevicePollStep? {
    val msg = message.orEmpty()
    return when {
        "authorization_pending" in msg -> DevicePollStep.Pending
        "slow_down" in msg -> DevicePollStep.SlowDown
        "expired_token" in msg -> DevicePollStep.Expired
        "access_denied" in msg -> DevicePollStep.Denied
        else -> null
    }
}

/**
 * Единый цикл опроса OAuth Device Code (RFC 8628): запрос кода, ожидание подтверждения и
 * обработка `authorization_pending` / `slow_down` / `expired_token` / `access_denied`.
 */
class DeviceCodeFlowRunner(
    private val client: GoogleAuthClient,
    private val clock: Clock = Clock.System,
) {
    sealed interface Result {
        data class Authorized(val tokens: OAuthTokens) : Result
        data object Expired : Result
        data class Denied(val message: String) : Result
        data class Failed(val error: DriveAuthException) : Result
    }

    suspend fun run(onCode: suspend (GoogleDeviceCodeResponse) -> Unit): Result {
        val response = client.requestDeviceCode()
        onCode(response)
        val expiresAt = clock.now() + response.expires_in.seconds
        var intervalSeconds = maxOf(response.interval, 1L)
        var waitedSeconds = 0L
        while (clock.now() < expiresAt && waitedSeconds < response.expires_in) {
            delay(intervalSeconds.seconds)
            waitedSeconds += intervalSeconds
            try {
                return Result.Authorized(client.pollDeviceToken(response.device_code))
            } catch (e: CancellationException) {
                throw e
            } catch (e: DriveAuthException) {
                when (e.toDevicePollStep()) {
                    DevicePollStep.Pending -> continue
                    DevicePollStep.SlowDown -> {
                        intervalSeconds += 5L
                        continue
                    }
                    DevicePollStep.Expired -> return Result.Expired
                    DevicePollStep.Denied -> return Result.Denied(e.message ?: "Access denied")
                    null -> return Result.Failed(e)
                }
            }
        }
        return Result.Expired
    }
}
