package dev.mimir.extension.platform

import dev.mimir.drive.auth.GoogleClientKind
import dev.mimir.drive.auth.OAuthTokens
import dev.mimir.drive.auth.TokenStorage

/** Порт побочных эффектов браузера: хранилища chrome.storage, OAuth-окно, вкладки, буфер обмена. */
interface ExtensionPlatform {
    /** Уведомляет, когда [key] в session-хранилище изменён любым контекстом расширения (`null` — ключ удалён). */
    fun observeSessionKey(key: String, onChanged: (String?) -> Unit)

    suspend fun getSessionString(key: String): String?
    suspend fun setSessionString(key: String, value: String)
    suspend fun removeSessionKey(key: String)
    suspend fun clearSession()

    suspend fun getLocalString(key: String): String?
    suspend fun setLocalString(key: String, value: String)
    suspend fun removeLocalKey(key: String)
    suspend fun clearLocal()

    fun getRedirectUrl(path: String = "oauth2"): String
    suspend fun launchWebAuthFlow(authUrl: String, interactive: Boolean = true): String
    /**
     * Открывает [authUrl] во вкладке и возвращает адрес, на который Google перенаправил браузер: он начинается
     * с [redirectPrefix] (loopback `http://127.0.0.1:<порт>/…`, как у Desktop-клиента). Сервер не нужен:
     * переход перехватывается до загрузки страницы, вкладка закрывается.
     */
    suspend fun launchLoopbackAuthFlow(authUrl: String, redirectPrefix: String): String

    fun openFullTab(relativePath: String?)
    fun openExternalUrl(url: String)
    suspend fun copyToClipboard(text: String)
}

/**
 * [TokenStorage]: access-токен живёт только в `chrome.storage.session` (исчезает с закрытием браузера),
 * refresh-токен и метаданные — в `chrome.storage.local`. Без access-токена [load] отдаёт просроченные
 * токены, и `RefreshingTokenSource` сам обновляет их по refresh-токену.
 */
class ExtensionTokenStorage(private val platform: ExtensionPlatform) : TokenStorage {
    override suspend fun load(): OAuthTokens? {
        val refresh = platform.getLocalString(REFRESH)
        val access = platform.getSessionString(ACCESS)
        if (refresh == null && access == null) return null
        return OAuthTokens(
            accessToken = access.orEmpty(),
            refreshToken = refresh,
            expiresAtEpochSeconds = if (access == null) 0L else platform.getSessionString(EXPIRES_AT)?.toLongOrNull() ?: 0L,
            scope = platform.getLocalString(SCOPE).orEmpty(),
            tokenType = platform.getLocalString(TOKEN_TYPE) ?: "Bearer",
            clientKind = platform.getLocalString(CLIENT_KIND)
                ?.let { name -> GoogleClientKind.entries.firstOrNull { it.name == name } }
                ?: GoogleClientKind.Device,
        )
    }

    override suspend fun save(tokens: OAuthTokens) {
        platform.setSessionString(ACCESS, tokens.accessToken)
        platform.setSessionString(EXPIRES_AT, tokens.expiresAtEpochSeconds.toString())
        if (tokens.refreshToken != null) platform.setLocalString(REFRESH, tokens.refreshToken!!)
        platform.setLocalString(SCOPE, tokens.scope)
        platform.setLocalString(TOKEN_TYPE, tokens.tokenType)
        platform.setLocalString(CLIENT_KIND, tokens.clientKind.name)
    }

    override suspend fun clear() {
        platform.removeSessionKey(ACCESS)
        platform.removeSessionKey(EXPIRES_AT)
        listOf(REFRESH, SCOPE, TOKEN_TYPE, CLIENT_KIND).forEach { platform.removeLocalKey(it) }
    }

    private companion object {
        const val ACCESS = "session.accessToken"
        const val EXPIRES_AT = "session.expiresAt"
        const val REFRESH = "auth.refreshToken"
        const val SCOPE = "auth.scope"
        const val TOKEN_TYPE = "auth.tokenType"
        const val CLIENT_KIND = "auth.clientKind"
    }
}
