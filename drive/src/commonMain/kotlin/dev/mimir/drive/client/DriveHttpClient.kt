package dev.mimir.drive.client

import dev.mimir.core.RemoteAuthException
import dev.mimir.core.RemoteUnavailableException
import dev.mimir.drive.auth.GoogleAuthClients
import dev.mimir.drive.auth.OAuthTokens
import dev.mimir.drive.auth.TokenStorage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException
import kotlin.coroutines.cancellation.CancellationException
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

    private inline fun <T> mapTokenErrors(block: () -> T): T = try {
        block()
    } catch (e: DriveAuthException) {
        val code = e.statusCode.value
        if (code >= 500 || code == 429) {
            throw RemoteUnavailableException("Google sign-in service unavailable: ${e.message}", e)
        }
        throw RemoteAuthException(e.message.orEmpty(), e)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw e.asRemoteUnavailableOrSelf()
    }

    suspend fun getValidToken(): String = mapTokenErrors {
        refreshMutex.withLock {
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

    suspend fun forceRefreshToken(): String = mapTokenErrors {
        refreshMutex.withLock {
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
            val response = try {
                block(token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw e.asRemoteUnavailableOrSelf()
            }
            if (response.status == HttpStatusCode.Unauthorized && !refreshedOn401) {
                refreshedOn401 = true
                token = forceRefreshToken()
                continue
            }

            val transient = response.status.value in 500..599
            if (response.status == HttpStatusCode.TooManyRequests || transient) {
                attempt++
                if (attempt < maxRetries) {
                    val backoffMs = (1L shl attempt) * 50L
                    delay(backoffMs)
                    continue
                }
                if (transient) {
                    throw RemoteUnavailableException("Google Drive is unavailable (${response.status})")
                }
            }

            return response
        }
    }
}

/**
 * Сетевой сбой (нет соединения, таймаут, не разрешился DNS) → [RemoteUnavailableException].
 * Ktor CIO на JVM при недоступном DNS бросает `java.nio.channels.UnresolvedAddressException`
 * (подкласс IllegalArgumentException); drive — common-код, поэтому она распознаётся по имени класса.
 * [CancellationException] сюда попадать не должна: её перехватывают раньше и пробрасывают как есть.
 */
private fun isNetworkFailure(e: Throwable): Boolean =
    e is IOException ||
        e is HttpRequestTimeoutException ||
        e::class.simpleName == "UnresolvedAddressException"

private fun Exception.asRemoteUnavailableOrSelf(): Exception =
    if (isNetworkFailure(this)) RemoteUnavailableException("Google Drive is unreachable: $message", this) else this

