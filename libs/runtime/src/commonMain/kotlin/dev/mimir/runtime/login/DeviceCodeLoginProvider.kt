package dev.mimir.runtime.login

import dev.mimir.client.DaemonDeviceCodeDto
import dev.mimir.client.DaemonLoginMethod
import dev.mimir.client.DaemonLoginPoll
import dev.mimir.client.DaemonLoginStart
import dev.mimir.drive.auth.AccessTokenSource
import dev.mimir.drive.auth.DevicePollStep
import dev.mimir.drive.auth.GoogleAuthClient
import dev.mimir.drive.auth.GoogleAuthClients
import dev.mimir.drive.auth.RefreshingTokenSource
import dev.mimir.drive.auth.TokenStorage
import dev.mimir.drive.auth.toDevicePollStep
import dev.mimir.drive.client.DriveAuthException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Вход по коду устройства (OAuth Device Flow): пользователь вводит код на другом устройстве.
 * Опрос Google идёт только по запросу [poll]; сетевые ошибки ([dev.mimir.drive.client.DriveHttpException]) пробрасываются.
 */
class DeviceCodeLoginProvider(
    private val client: GoogleAuthClient,
    private val tokenStorage: TokenStorage,
    override val tokenSource: AccessTokenSource = RefreshingTokenSource(tokenStorage, GoogleAuthClients(desktop = null, device = client)),
) : LoginProvider {
    override val method: DaemonLoginMethod get() = DaemonLoginMethod.DeviceCode

    private class Attempt(val deviceCode: String, var result: DaemonLoginPoll = DaemonLoginPoll.Pending)

    private val mutex = Mutex()
    private var attempt: Attempt? = null

    override suspend fun start(): DaemonLoginStart = mutex.withLock {
        val res = client.requestDeviceCode()
        attempt = Attempt(res.device_code)
        DaemonLoginStart.ShowDeviceCode(
            DaemonDeviceCodeDto(
                deviceCode = res.device_code,
                userCode = res.user_code,
                verificationUrl = res.verification_url,
                expiresInSeconds = res.expires_in,
                intervalSeconds = res.interval,
            ),
        )
    }

    override suspend fun poll(): DaemonLoginPoll = mutex.withLock {
        val current = attempt ?: return@withLock DaemonLoginPoll.Failed("Вход не начат")
        // Код устройства одноразовый: после итога Google больше не опрашивается.
        if (current.result != DaemonLoginPoll.Pending) return@withLock current.result
        val tokens = try {
            client.pollDeviceToken(current.deviceCode)
        } catch (e: DriveAuthException) {
            return@withLock when (e.toDevicePollStep()) {
                DevicePollStep.Pending, DevicePollStep.SlowDown -> DaemonLoginPoll.Pending
                DevicePollStep.Expired, DevicePollStep.Denied, null ->
                    DaemonLoginPoll.Failed(e.message.orEmpty()).also { current.result = it }
            }
        }
        val email = client.emailOrNull(tokens.accessToken)
        tokenStorage.save(tokens)
        DaemonLoginPoll.Authorized(email).also { current.result = it }
    }

    override suspend fun cancel() = mutex.withLock { attempt = null }

    override suspend fun signOut() {
        tokenStorage.clear()
    }
}
