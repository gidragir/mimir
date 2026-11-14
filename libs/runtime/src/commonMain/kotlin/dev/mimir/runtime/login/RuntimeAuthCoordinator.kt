package dev.mimir.runtime.login

import dev.mimir.client.DaemonLoginMethod
import dev.mimir.client.DaemonLoginPoll
import dev.mimir.client.DaemonLoginStart
import dev.mimir.drive.auth.AccessTokenSource
import dev.mimir.runtime.AccountStore
import dev.mimir.runtime.SyncNotConfiguredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.logging.Logger

private val logger = Logger.getLogger(RuntimeAuthCoordinator::class.java.name)

/**
 * Координатор OAuth-входа для [dev.mimir.runtime.MimirRuntime]: управляет активной попыткой входа,
 * сохранением учётных данных и выбором источника токенов для движка синхронизации.
 */
internal class RuntimeAuthCoordinator(
    private val logins: LoginRegistry,
    private val account: AccountStore,
) {
    private val loginMutex = Mutex()
    private var activeLogin: LoginProvider? = null
    private var loginCompleted = false

    @Volatile
    var accountEmail: String? = null
        private set

    suspend fun initAccountEmail() {
        accountEmail = account.email()
    }

    fun methods(): Set<DaemonLoginMethod> = logins.methods()

    suspend fun tokenSource(): AccessTokenSource? =
        account.method()?.let { logins[it] }?.tokenSource

    suspend fun startLogin(method: DaemonLoginMethod): DaemonLoginStart = loginMutex.withLock {
        val provider = logins[method] ?: throw SyncNotConfiguredException(method.notConfiguredMessage())
        // Попытка другим способом (например, вкладка браузера после «Отмена») не должна перезаписать новый вход.
        activeLogin?.takeIf { it !== provider }?.cancel()
        activeLogin = null
        val start = provider.start()
        activeLogin = provider
        loginCompleted = false
        start
    }

    /** Первый [DaemonLoginPoll.Authorized] попытки запоминает аккаунт и вызывает [onAuthorized] ровно один раз. */
    suspend fun pollLogin(onAuthorized: suspend () -> Unit): DaemonLoginPoll = loginMutex.withLock {
        val provider = activeLogin ?: return@withLock DaemonLoginPoll.Failed("Вход не начат")
        val result = provider.poll()
        if (result !is DaemonLoginPoll.Authorized || loginCompleted) return@withLock result
        try {
            // Токены уже сохранены провайдером: запоминаем аккаунт и перезапускаем синхронизацию.
            account.save(result.email, provider.method)
            accountEmail = result.email
            onAuthorized()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warning("Saving sign-in failed: ${e.javaClass.name}")
            return@withLock DaemonLoginPoll.Failed("Не удалось сохранить вход")
        }
        loginCompleted = true
        result
    }

    /** Выход: провайдер забывает учётные данные, `account.json` удаляется; vault и индекс не трогаются. */
    suspend fun signOut(teardownSync: suspend () -> Unit) = loginMutex.withLock {
        account.method()?.let { logins[it] }?.signOut()
        account.delete()
        accountEmail = null
        activeLogin = null
        teardownSync()
    }

    fun close() {
        logins.close()
    }
}
