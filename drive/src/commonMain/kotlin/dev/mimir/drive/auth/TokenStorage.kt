package dev.mimir.drive.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface TokenStorage {
    suspend fun load(): OAuthTokens?
    suspend fun save(tokens: OAuthTokens)
    suspend fun clear()
}

class InMemoryTokenStorage(initial: OAuthTokens? = null) : TokenStorage {
    private val mutex = Mutex()
    private var current: OAuthTokens? = initial

    override suspend fun load(): OAuthTokens? = mutex.withLock { current }

    override suspend fun save(tokens: OAuthTokens) {
        mutex.withLock { current = tokens }
    }

    override suspend fun clear() {
        mutex.withLock { current = null }
    }
}
