package dev.mimir.android.auth

class FakeGisAuthorizer(
    var available: Boolean = true,
    var tokenResult: String? = "test-token",
) : GisAuthorizer {
    var authorizeCallCount: Int = 0
    val clearedTokens = mutableListOf<String>()
    var revoked: Boolean = false

    override suspend fun authorizeSilently(): String? {
        authorizeCallCount++
        return tokenResult
    }

    override suspend fun clearToken(token: String) {
        clearedTokens.add(token)
    }

    override suspend fun revoke() {
        revoked = true
    }

    override fun isAvailable(): Boolean = available
}
