package dev.mimir.obsidian.state

import dev.mimir.drive.auth.OAuthTokens
import dev.mimir.drive.auth.TokenStorage
import dev.mimir.obsidian.platform.LocalStorageFn
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json

/**
 * Хранилище секретов и токенов на устройстве через Obsidian local storage.
 * Хранит OAuth-токены, 80-байтный ключ RcloneCipher (base64), канарейку и change token.
 */
class DeviceSecretStore(
    private val storage: LocalStorageFn,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : TokenStorage {

    companion object {
        const val KEY_TOKENS = "mimir.tokens"
        const val KEY_CIPHER_KEY = "mimir.key"
        const val KEY_CANARY_HASH = "mimir.canaryHash"
        const val KEY_CHANGE_TOKEN = "mimir.changeToken"
    }

    override suspend fun load(): OAuthTokens? {
        val raw = storage.load(KEY_TOKENS) ?: return null
        return try {
            json.decodeFromString<OAuthTokens>(raw)
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun save(tokens: OAuthTokens) {
        val text = json.encodeToString(OAuthTokens.serializer(), tokens)
        storage.save(KEY_TOKENS, text)
    }

    override suspend fun clear() {
        storage.save(KEY_TOKENS, null)
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun loadKey(): ByteArray? {
        val raw = storage.load(KEY_CIPHER_KEY) ?: return null
        return try {
            Base64.decode(raw)
        } catch (_: Throwable) {
            null
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun saveKey(key: ByteArray) {
        storage.save(KEY_CIPHER_KEY, Base64.encode(key))
    }

    suspend fun loadCanaryHash(): String? = storage.load(KEY_CANARY_HASH)

    suspend fun saveCanaryHash(hash: String) {
        storage.save(KEY_CANARY_HASH, hash)
    }

    suspend fun loadChangeToken(): String? = storage.load(KEY_CHANGE_TOKEN)

    suspend fun saveChangeToken(token: String?) {
        storage.save(KEY_CHANGE_TOKEN, token)
    }

    suspend fun clearAll() {
        storage.save(KEY_TOKENS, null)
        storage.save(KEY_CIPHER_KEY, null)
        storage.save(KEY_CANARY_HASH, null)
        storage.save(KEY_CHANGE_TOKEN, null)
    }
}
