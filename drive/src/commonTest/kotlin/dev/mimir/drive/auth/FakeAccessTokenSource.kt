package dev.mimir.drive.auth

import dev.mimir.drive.client.DriveAuthException
import io.ktor.http.HttpStatusCode

/** Фейк для тестов: [token] — первый в очереди, [refresh] отбрасывает его и отдаёт следующий. */
class FakeAccessTokenSource(
    var tokens: ArrayDeque<String>,
    var requireSignIn: Boolean = false,
) : AccessTokenSource {
    override suspend fun token(): String {
        if (requireSignIn) throw signIn()
        return tokens.firstOrNull() ?: throw signIn()
    }

    override suspend fun refresh(): String {
        if (requireSignIn) throw signIn()
        if (tokens.isNotEmpty()) tokens.removeFirst()
        return tokens.firstOrNull() ?: throw signIn()
    }

    override suspend fun hasCredentials(): Boolean = !requireSignIn && tokens.isNotEmpty()

    private fun signIn() = DriveAuthException(HttpStatusCode.Unauthorized, "Sign in required")
}
