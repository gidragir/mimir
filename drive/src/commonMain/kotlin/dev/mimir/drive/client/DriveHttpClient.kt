package dev.mimir.drive.client

import dev.mimir.drive.auth.GoogleAuthClients
import dev.mimir.drive.auth.OAuthTokens
import dev.mimir.drive.auth.TokenStorage
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

class DriveHttpClient(
    val httpClient: HttpClient,
    val tokenStorage: TokenStorage,
    val authClients: GoogleAuthClients,
    val clock: Clock = Clock.System,
) {
    private val refreshMutex = Mutex()

    private suspend fun refreshWith(tokens: OAuthTokens, refresh: String): OAuthTokens {
        val client = authClients.forKind(tokens.clientKind)
            ?: throw DriveAuthException(
                HttpStatusCode.Unauthorized,
                "Sign in again: no Google client for ${tokens.clientKind}",
            )
        return client.refreshTokens(refresh)
    }

    suspend fun getValidToken(): String {
        return refreshMutex.withLock {
            val tokens = tokenStorage.load()
                ?: throw DriveAuthException(HttpStatusCode.Unauthorized, "No OAuth tokens stored")
            if (tokens.isExpired(clock)) {
                val refresh = tokens.refreshToken
                    ?: throw DriveAuthException(HttpStatusCode.Unauthorized, "Access token expired and no refresh token available")
                val refreshed = refreshWith(tokens, refresh)
                tokenStorage.save(refreshed)
                refreshed.accessToken
            } else {
                tokens.accessToken
            }
        }
    }

    suspend fun forceRefreshToken(): String {
        return refreshMutex.withLock {
            val tokens = tokenStorage.load()
                ?: throw DriveAuthException(HttpStatusCode.Unauthorized, "No OAuth tokens stored")
            val refresh = tokens.refreshToken
                ?: throw DriveAuthException(HttpStatusCode.Unauthorized, "No refresh token available")
            val refreshed = refreshWith(tokens, refresh)
            tokenStorage.save(refreshed)
            refreshed.accessToken
        }
    }

    suspend fun executeWithRetry(
        maxRetries: Int = 3,
        block: suspend (token: String) -> HttpResponse,
    ): HttpResponse {
        var token = getValidToken()
        var attempt = 0
        var refreshedOn401 = false

        while (true) {
            val response = block(token)
            if (response.status == HttpStatusCode.Unauthorized && !refreshedOn401) {
                refreshedOn401 = true
                token = forceRefreshToken()
                continue
            }

            if (response.status == HttpStatusCode.TooManyRequests || response.status == HttpStatusCode.ServiceUnavailable) {
                attempt++
                if (attempt < maxRetries) {
                    val backoffMs = (1L shl attempt) * 50L
                    delay(backoffMs)
                    continue
                }
            }

            return response
        }
    }
}
