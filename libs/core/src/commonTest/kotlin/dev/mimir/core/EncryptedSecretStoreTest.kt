package dev.mimir.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class EncryptedSecretStoreTest {
    private class MapValues : SecretValueStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    /** Обратимое «шифрование» для проверки обвязки: xor. */
    private object XorCipher : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun decrypt(encrypted: ByteArray) = encrypt(encrypted)
    }

    private object BrokenCipher : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(encrypted: ByteArray): ByteArray = throw IllegalStateException("key invalidated")
    }

    @Test
    fun `round trips a secret and never stores it in clear text`() = runTest {
        val values = MapValues()
        val store = EncryptedSecretStore(values, XorCipher)
        store.put("vault-password", "p@ssw0rd пароль")
        assertEquals("p@ssw0rd пароль", store.get("vault-password"))
        assertFalse(values.map.values.any { it.contains("p@ssw0rd") })
    }

    @Test
    fun `returns null for a missing key and after remove`() = runTest {
        val store = EncryptedSecretStore(MapValues(), XorCipher)
        assertNull(store.get("x"))
        store.put("x", "1")
        store.remove("x")
        assertNull(store.get("x"))
    }

    @Test
    fun `an undecryptable secret reads as absent and is dropped`() = runTest {
        val values = MapValues()
        EncryptedSecretStore(values, XorCipher).put("k", "v")
        val store = EncryptedSecretStore(values, BrokenCipher)
        assertNull(store.get("k"))
        assertNull(values.get("k"))
    }
}
