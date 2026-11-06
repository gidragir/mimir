package dev.mimir.android.auth

import dev.mimir.drive.auth.AccessTokenSource
import dev.mimir.drive.client.DriveAuthException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class GisTokenSource(
    val authorizer: GisAuthorizer,
    private val clock: Clock = Clock.System,
    private val ttl: Duration = 50.minutes,
) : AccessTokenSource {
    private val mutex = Mutex()
    private var cachedToken: String? = null
    private var expiresAt: Instant? = null

    override suspend fun token(): String = mutex.withLock {
        val now = clock.now()
        val currentToken = cachedToken
        val currentExpiry = expiresAt
        if (currentToken != null && currentExpiry != null && now < currentExpiry) {
            return@withLock currentToken
        }
        fetchToken(now)
    }

    override suspend fun refresh(): String = mutex.withLock {
        val oldToken = cachedToken
        cachedToken = null
        expiresAt = null
        if (oldToken != null) {
            authorizer.clearToken(oldToken)
        }
        fetchToken(clock.now())
    }

    override suspend fun hasCredentials(): Boolean = mutex.withLock {
        val now = clock.now()
        val currentToken = cachedToken
        val currentExpiry = expiresAt
        if (currentToken != null && currentExpiry != null && now < currentExpiry) {
            return@withLock true
        }
        val token = try {
            authorizer.authorizeSilently()
        } catch (_: java.io.IOException) {
            return@withLock authorizer.hasSavedAccount()
        }
        if (token != null) {
            cachedToken = token
            expiresAt = now + ttl
            true
        } else {
            false
        }
    }

    suspend fun revoke(): Unit = mutex.withLock {
        cachedToken = null
        expiresAt = null
        authorizer.revoke()
    }

    private suspend fun fetchToken(now: Instant): String {
        val token = authorizer.authorizeSilently()
            ?: throw DriveAuthException(HttpStatusCode.Unauthorized, "Interactive sign-in required")
        cachedToken = token
        expiresAt = now + ttl
        return token
    }
}
