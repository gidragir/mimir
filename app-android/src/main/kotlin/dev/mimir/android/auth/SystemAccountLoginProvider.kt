package dev.mimir.android.auth

import dev.mimir.client.DaemonLoginMethod
import dev.mimir.client.DaemonLoginPoll
import dev.mimir.client.DaemonLoginStart
import dev.mimir.runtime.login.LoginProvider
import kotlinx.coroutines.CancellationException

class SystemAccountLoginProvider(
    override val tokenSource: GisTokenSource,
    private val fetchEmail: suspend (accessToken: String) -> String?,
) : LoginProvider {
    override val method: DaemonLoginMethod get() = DaemonLoginMethod.SystemAccount

    override suspend fun start(): DaemonLoginStart {
        return DaemonLoginStart.Started
    }

    override suspend fun poll(): DaemonLoginPoll {
        return try {
            val token = tokenSource.token()
            val email = fetchEmail(token)
            DaemonLoginPoll.Authorized(email)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            DaemonLoginPoll.Failed("Не удалось войти в Google")
        }
    }

    override suspend fun signOut() {
        tokenSource.revoke()
    }
}
