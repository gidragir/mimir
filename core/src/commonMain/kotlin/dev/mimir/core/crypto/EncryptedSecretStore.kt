package dev.mimir.core

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Хранилище строк по ключу (на Android — SharedPreferences). */
interface SecretValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** Симметричное шифрование значения ключом, который не покидает платформенное хранилище (Android Keystore). */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(encrypted: ByteArray): ByteArray
}

/**
 * [SecretStore] поверх [SecretValueStore] и [SecretCipher]: на диск попадает только шифртекст в base64.
 * Значение, которое не расшифровалось (ключ пересоздан, данные повреждены), читается как отсутствующее и удаляется.
 */
@OptIn(ExperimentalEncodingApi::class)
class EncryptedSecretStore(
    private val values: SecretValueStore,
    private val cipher: SecretCipher,
) : SecretStore {
    override suspend fun get(key: String): String? {
        val stored = values.get(key) ?: return null
        return try {
            cipher.decrypt(Base64.decode(stored)).decodeToString()
        } catch (_: Exception) {
            values.remove(key)
            null
        }
    }

    override suspend fun put(key: String, value: String) {
        values.put(key, Base64.encode(cipher.encrypt(value.encodeToByteArray())))
    }

    override suspend fun remove(key: String) = values.remove(key)
}
