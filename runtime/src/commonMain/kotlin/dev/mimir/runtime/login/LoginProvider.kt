package dev.mimir.runtime.login

import dev.mimir.client.DaemonLoginMethod
import dev.mimir.client.DaemonLoginPoll
import dev.mimir.client.DaemonLoginStart
import dev.mimir.drive.auth.AccessTokenSource

/** Способ входа в Google (стратегия): начинает вход, сообщает его состояние и даёт токены для Drive. */
interface LoginProvider {
    val method: DaemonLoginMethod

    /** Токены для Drive после входа этим способом. */
    val tokenSource: AccessTokenSource

    suspend fun start(): DaemonLoginStart

    /** Authorized — учётные данные уже сохранены провайдером. */
    suspend fun poll(): DaemonLoginPoll

    suspend fun signOut()

    /** Прекращает начатую попытку: после возврата она ничего не сохранит. */
    suspend fun cancel() {}

    fun close() {}
}

/** Email аккаунта для показа; сбой профиля не мешает входу — токены важнее. */
internal suspend fun dev.mimir.drive.auth.GoogleAuthClient.emailOrNull(accessToken: String): String? = try {
    fetchAccount(accessToken).email.takeIf { it.isNotEmpty() }
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (_: Exception) {
    null
}
