package dev.mimir.drive.auth

import dev.mimir.core.SecretStore
import kotlinx.serialization.json.Json

/** [TokenStorage] поверх [SecretStore]: токены Google лежат в платформенном защищённом хранилище (Android Keystore). */
class SecretStoreTokenStorage(
    private val secrets: SecretStore,
    private val key: String = "google-oauth-tokens",
    private val json: Json = Json { ignoreUnknownKeys = true },
) : TokenStorage {
    override suspend fun load(): OAuthTokens? =
        secrets.get(key)?.let { runCatching { json.decodeFromString<OAuthTokens>(it) }.getOrNull() }

    override suspend fun save(tokens: OAuthTokens) = secrets.put(key, json.encodeToString(OAuthTokens.serializer(), tokens))

    override suspend fun clear() = secrets.remove(key)
}
