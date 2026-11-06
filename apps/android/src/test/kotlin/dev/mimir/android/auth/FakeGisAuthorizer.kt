package dev.mimir.android.auth

class FakeGisAuthorizer(
    var available: Boolean = true,
    var tokenResult: String? = "test-token",
    var savedAccount: Boolean = false,
    var throwOnAuthorize: Throwable? = null,
) : GisAuthorizer {
    var authorizeCallCount: Int = 0
    val clearedTokens = mutableListOf<String>()
    var revoked: Boolean = false

    override suspend fun authorizeSilently(): String? {
        authorizeCallCount++
        throwOnAuthorize?.let { throw it }
        return tokenResult
    }

    override suspend fun clearToken(token: String) {
        clearedTokens.add(token)
    }

    override suspend fun revoke() {
        revoked = true
    }

    override fun isAvailable(): Boolean = available
    override fun hasSavedAccount(): Boolean = savedAccount
}
