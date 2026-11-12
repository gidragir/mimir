package dev.mimir.obsidian.sync

import dev.mimir.core.RemoteUnavailableException
import dev.mimir.core.sync.SyncIndex
import dev.mimir.drive.auth.GoogleAuthClient
import dev.mimir.drive.auth.GoogleClientCredentials
import dev.mimir.drive.auth.GoogleClientKind
import dev.mimir.drive.client.DriveAuthException
import dev.mimir.obsidian.PluginBuildConfig
import dev.mimir.obsidian.net.NetworkUnavailableException
import dev.mimir.obsidian.state.DeviceSecretStore
import dev.mimir.obsidian.state.ObsidianSyncStateStore
import dev.mimir.obsidian.state.PluginSettings
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Фабрика клиента авторизации Google по коду устройства.
 * Возвращает `null`, если не настроен ни custom client ID в настройках, ни buildconfig ID.
 */
fun deviceAuthClient(
    http: HttpClient,
    settings: PluginSettings,
    defaultClientId: String = PluginBuildConfig.GOOGLE_DEVICE_CLIENT_ID,
    defaultClientSecret: String = PluginBuildConfig.GOOGLE_DEVICE_CLIENT_SECRET,
): GoogleAuthClient? {
    val clientId = settings.customClientId.ifBlank { defaultClientId }
    if (clientId.isBlank()) return null
    val clientSecret = settings.customClientSecret.ifBlank { defaultClientSecret }.ifBlank { null }
    val credentials = GoogleClientCredentials(
        kind = GoogleClientKind.Device,
        clientId = clientId,
        clientSecret = clientSecret,
    )
    return GoogleAuthClient(http, credentials)
}

/**
 * Состояния процесса входа по коду устройства (RFC 8628).
 */
sealed interface SignInState {
    data object Idle : SignInState
    data object Requesting : SignInState
    data class AwaitingUser(val userCode: String, val verificationUrl: String, val expiresAt: Instant) : SignInState
    data class Success(val email: String?) : SignInState
    data object Expired : SignInState
    data class Failed(val message: String) : SignInState
    data object Offline : SignInState
}

/**
 * Контроллер сессии входа по коду устройства.
 */
class DeviceSignIn(
    private val client: GoogleAuthClient,
    private val tokens: DeviceSecretStore,
    private val clock: Clock = Clock.System,
) {
    private val _state = MutableStateFlow<SignInState>(SignInState.Idle)
    val state: StateFlow<SignInState> = _state.asStateFlow()

    private var activeJob: Job? = null

    suspend fun run() {
        coroutineScope {
            activeJob = coroutineContext.job
            try {
                execute()
            } finally {
                activeJob = null
            }
        }
    }

    fun cancel() {
        activeJob?.cancel()
        _state.value = SignInState.Idle
    }

    private suspend fun execute() {
        _state.value = SignInState.Requesting

        val codeResponse = try {
            client.requestDeviceCode()
        } catch (e: CancellationException) {
            _state.value = SignInState.Idle
            throw e
        } catch (e: Throwable) {
            if (isNetworkError(e)) {
                _state.value = SignInState.Offline
            } else {
                _state.value = SignInState.Failed(e.message ?: "Failed to request device code")
            }
            return
        }

        val expiresAt = clock.now() + codeResponse.expires_in.seconds
        _state.value = SignInState.AwaitingUser(
            userCode = codeResponse.user_code,
            verificationUrl = codeResponse.verification_url,
            expiresAt = expiresAt,
        )

        val interval = maxOf(codeResponse.interval, 1)

        while (clock.now() < expiresAt) {
            try {
                delay(interval.seconds)
            } catch (e: CancellationException) {
                _state.value = SignInState.Idle
                throw e
            }

            val oauthTokens = try {
                client.pollDeviceToken(codeResponse.device_code)
            } catch (e: CancellationException) {
                _state.value = SignInState.Idle
                throw e
            } catch (e: DriveAuthException) {
                val msg = e.message.orEmpty()
                if ("authorization_pending" in msg) {
                    continue
                }
                if ("slow_down" in msg) {
                    delay(interval.seconds)
                    continue
                }
                if ("expired_token" in msg) {
                    _state.value = SignInState.Expired
                    return
                }
                if ("access_denied" in msg) {
                    _state.value = SignInState.Failed("Access denied")
                    return
                }
                _state.value = SignInState.Failed(e.message ?: "Authentication failed")
                return
            } catch (e: Throwable) {
                if (isNetworkError(e)) {
                    _state.value = SignInState.Offline
                } else {
                    _state.value = SignInState.Failed(e.message ?: "Authentication failed")
                }
                return
            }

            tokens.save(oauthTokens)
            val email = try {
                client.fetchAccount(oauthTokens.accessToken).email.ifBlank { null }
            } catch (_: Throwable) {
                null
            }
            _state.value = SignInState.Success(email)
            return
        }

        _state.value = SignInState.Expired
    }

    private fun isNetworkError(e: Throwable): Boolean {
        if (e is NetworkUnavailableException || e is RemoteUnavailableException || e is kotlinx.io.IOException) return true
        val msg = e.message.orEmpty()
        return "Network is down" in msg || "net::" in msg || "Failed to fetch" in msg
    }
}

/**
 * Выход из аккаунта: отзыв токена в Google (ошибки отзыва не блокируют выход), очистка секретов и сброс индекса.
 */
suspend fun signOut(
    client: GoogleAuthClient?,
    tokens: DeviceSecretStore,
    state: ObsidianSyncStateStore,
) {
    val currentTokens = tokens.load()
    if (client != null && currentTokens != null) {
        val tokenToRevoke = currentTokens.refreshToken ?: currentTokens.accessToken
        try {
            client.revokeToken(tokenToRevoke)
        } catch (_: Throwable) {
            // Ошибка отзыва не мешает очистке
        }
    }
    tokens.clearAll()
    state.save(SyncIndex())
}
