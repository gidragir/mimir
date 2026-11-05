package dev.mimir.drive.auth

import dev.mimir.drive.client.DriveAuthException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/** Источник OAuth access-токена для Drive (стратегия: refresh-токен, Google Identity Services и т.п.). */
interface AccessTokenSource {
    /** Действующий токен; [DriveAuthException] 401 — нужен вход. */
    suspend fun token(): String

    /** Отбросить текущий токен (после 401) и получить новый. */
    suspend fun refresh(): String

    suspend fun hasCredentials(): Boolean
}

/** Токены из [TokenStorage], обновляются по refresh-токену через [GoogleAuthClients]. */
class RefreshingTokenSource(
    private val storage: TokenStorage,
    private val clients: GoogleAuthClients,
    private val clock: Clock = Clock.System,
) : AccessTokenSource {
    private val mutex = Mutex()

    override suspend fun token(): String = mutex.withLock {
        val tokens = storage.load()
            ?: throw DriveAuthException(HttpStatusCode.Unauthorized, "No OAuth tokens stored")
        if (tokens.isExpired(clock)) {
            val refresh = tokens.refreshToken
                ?: throw DriveAuthException(HttpStatusCode.Unauthorized, "Access token expired and no refresh token available")
            refreshWith(tokens, refresh)
        } else {
            tokens.accessToken
        }
    }

    override suspend fun refresh(): String = mutex.withLock {
        val tokens = storage.load()
            ?: throw DriveAuthException(HttpStatusCode.Unauthorized, "No OAuth tokens stored")
        val refresh = tokens.refreshToken
            ?: throw DriveAuthException(HttpStatusCode.Unauthorized, "No refresh token available")
        refreshWith(tokens, refresh)
    }

    override suspend fun hasCredentials(): Boolean = storage.load() != null

    private suspend fun refreshWith(tokens: OAuthTokens, refresh: String): String {
        val client = clients.forKind(tokens.clientKind)
            ?: throw DriveAuthException(
                HttpStatusCode.Unauthorized,
                "Sign in again: no Google client for ${tokens.clientKind}",
            )
        val refreshed = client.refreshTokens(refresh)
        storage.save(refreshed)
        return refreshed.accessToken
    }
}
