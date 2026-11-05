package dev.mimir.drive

import dev.mimir.core.SecretStore
import dev.mimir.drive.auth.OAuthTokens
import dev.mimir.drive.auth.SecretStoreTokenStorage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SecretStoreTokenStorageTest {
    private class MapSecrets : SecretStore {
        val map = mutableMapOf<String, String>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(key: String, value: String) { map[key] = value }
        override suspend fun remove(key: String) { map.remove(key) }
    }

    @Test
    fun `saves loads and clears tokens through the secret store`() = runTest {
        val secrets = MapSecrets()
        val storage = SecretStoreTokenStorage(secrets)
        assertNull(storage.load())
        val tokens = OAuthTokens("access", "refresh", 123L)
        storage.save(tokens)
        assertEquals(tokens, storage.load())
        storage.clear()
        assertNull(storage.load())
        assertEquals(emptyMap(), secrets.map)
    }

    @Test
    fun `garbage in the store reads as no tokens`() = runTest {
        val secrets = MapSecrets().also { it.map["google-oauth-tokens"] = "not json" }
        assertNull(SecretStoreTokenStorage(secrets).load())
    }
}
