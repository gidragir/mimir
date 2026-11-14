package dev.mimir.obsidian.state

import dev.mimir.drive.auth.GoogleClientKind
import dev.mimir.drive.auth.OAuthTokens
import dev.mimir.obsidian.fakes.FakeLocalStorage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceSecretStoreTest {

    @Test
    fun tokensRoundTrip() = runTest {
        val storage = FakeLocalStorage()
        val store = DeviceSecretStore(storage)

        val tokens = OAuthTokens(
            accessToken = "ya29.test-access-token",
            refreshToken = "1//test-refresh-token",
            expiresAtEpochSeconds = 1800000000L,
            scope = "https://www.googleapis.com/auth/drive.file",
            tokenType = "Bearer",
            clientKind = GoogleClientKind.Device,
        )

        store.save(tokens)
        val loaded = store.load()

        assertEquals(tokens, loaded)
    }

    @Test
    fun keyRoundTripIs80Bytes() = runTest {
        val storage = FakeLocalStorage()
        val store = DeviceSecretStore(storage)

        // 80 bytes key for rclone crypt
        val key = ByteArray(80) { (it * 3 + 7).toByte() }

        store.saveKey(key)
        val loaded = store.loadKey()

        assertEquals(80, loaded?.size)
        assertTrue(key.contentEquals(loaded!!))
    }

    @Test
    fun clearAllRemovesEverything() = runTest {
        val storage = FakeLocalStorage()
        val store = DeviceSecretStore(storage)

        val tokens = OAuthTokens(
            accessToken = "access",
            refreshToken = "refresh",
            expiresAtEpochSeconds = 1000L,
        )
        val key = ByteArray(80) { it.toByte() }

        store.save(tokens)
        store.saveKey(key)
        store.saveCanaryHash("hash-abc")
        store.saveChangeToken("token-xyz")
        store.saveEmail("user@example.com")

        assertEquals("user@example.com", store.loadEmail())

        store.clearAll()

        assertNull(store.load())
        assertNull(store.loadKey())
        assertNull(store.loadCanaryHash())
        assertNull(store.loadChangeToken())
        assertNull(store.loadEmail())
    }

    @Test
    fun corruptTokensJsonReturnsNull() = runTest {
        val storage = FakeLocalStorage()
        val store = DeviceSecretStore(storage)

        storage.save(DeviceSecretStore.KEY_TOKENS, "{ not a valid json }")
        assertNull(store.load())
    }
}
