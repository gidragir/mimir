package dev.mimir.runtime.login

import dev.mimir.client.DaemonLoginMethod
import dev.mimir.client.DaemonLoginPoll
import dev.mimir.client.DaemonLoginStart
import dev.mimir.drive.auth.AccessTokenSource
import dev.mimir.drive.auth.GoogleAuthClient
import dev.mimir.drive.auth.GoogleAuthClients
import dev.mimir.drive.auth.LoginCallback
import dev.mimir.drive.auth.RefreshingTokenSource
import dev.mimir.drive.auth.TokenStorage
import dev.mimir.drive.auth.newBrowserLogin
import dev.mimir.drive.client.DriveHttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private val logger = java.util.logging.Logger.getLogger(BrowserLoginProvider::class.java.name)

/**
 * Вход через системный браузер: PKCE, Desktop-клиент Google и одноразовый [BrowserLoginReceiver] на `127.0.0.1`.
 * Новая попытка отменяет предыдущую и дожидается её, чтобы старая не сохранила вход параллельно.
 */
class BrowserLoginProvider(
    private val client: GoogleAuthClient,
    private val tokenStorage: TokenStorage,
    private val scope: CoroutineScope,
    /** Блокирующие операции приёмника редиректа; JVM/Android передают `Dispatchers.IO`. */
    private val ioDispatcher: CoroutineDispatcher,
    private val timeout: Duration = 5.minutes,
    override val tokenSource: AccessTokenSource = RefreshingTokenSource(tokenStorage, GoogleAuthClients(desktop = client, device = null)),
) : LoginProvider {
    override val method: DaemonLoginMethod get() = DaemonLoginMethod.Browser

    private class Attempt(val receiver: BrowserLoginReceiver, val job: Job, val state: MutableStateFlow<DaemonLoginPoll>)

    private val mutex = Mutex()
    private var attempt: Attempt? = null

    override suspend fun start(): DaemonLoginStart = mutex.withLock {
        cancelAttempt()
        val receiver = BrowserLoginReceiver(ioDispatcher, timeout)
        val request = try {
            receiver.start()
            client.newBrowserLogin(receiver.port).also { receiver.expectedState = it.state }
        } catch (e: Exception) {
            receiver.close()
            throw e
        }
        val state = MutableStateFlow<DaemonLoginPoll>(DaemonLoginPoll.Pending)
        val job = scope.launch {
            state.value = when (val callback = receiver.awaitCallback()) {
                null -> DaemonLoginPoll.Failed("Время ожидания истекло")
                LoginCallback.Denied -> DaemonLoginPoll.Failed("Вход отменён")
                is LoginCallback.Invalid -> DaemonLoginPoll.Failed("Вход не удался")
                is LoginCallback.Code -> complete(callback.code, request.verifier, request.redirectUri)
            }
        }
        attempt = Attempt(receiver, job, state)
        DaemonLoginStart.OpenUrl(request.authUrl)
    }

    private suspend fun complete(code: String, verifier: String, redirectUri: String): DaemonLoginPoll {
        val tokens = try {
            client.exchangeAuthorizationCode(code, verifier, redirectUri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveHttpException) {
            logger.warning("Google rejected the authorization code: ${e.javaClass.simpleName}, HTTP ${e.statusCode}")
            return DaemonLoginPoll.Failed("Google не принял вход")
        } catch (e: Exception) {
            logger.warning("Token exchange failed: ${e.javaClass.name}")
            return DaemonLoginPoll.Failed("Не удалось связаться с Google")
        }
        val email = client.emailOrNull(tokens.accessToken)
        // Отменённая (перезапущенная) попытка входа не должна ничего сохранять.
        currentCoroutineContext().ensureActive()
        return try {
            tokenStorage.save(tokens)
            DaemonLoginPoll.Authorized(email)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warning("Saving sign-in failed: ${e.javaClass.name}")
            DaemonLoginPoll.Failed("Не удалось сохранить вход")
        }
    }

    override suspend fun cancel() = mutex.withLock { cancelAttempt() }

    /** Под [mutex]. Задача попытки mutex не берёт, поэтому join под ним не взаимоблокируется. */
    private suspend fun cancelAttempt() {
        attempt?.let {
            it.job.cancel()
            it.receiver.close()
            it.job.join()
        }
        attempt = null
    }

    override suspend fun poll(): DaemonLoginPoll =
        mutex.withLock { attempt?.state?.value } ?: DaemonLoginPoll.Failed("Вход не начат")

    override suspend fun signOut() {
        tokenStorage.clear()
    }

    override fun close() {
        attempt?.receiver?.close()
    }
}
