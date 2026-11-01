package dev.mimir.extension.platform

import dev.mimir.core.Account
import dev.mimir.extension.ui.AccountCodec

/** In-memory двойник [ExtensionPlatform]: session/local хранилища живут в картах, побочные эффекты записываются. */
class FakeExtensionPlatform : ExtensionPlatform {
    private val session = mutableMapOf<String, String>()
    private val local = mutableMapOf<String, String>()

    val openedTabs = mutableListOf<String?>()
    val openedExternalUrls = mutableListOf<String>()
    val clipboard = mutableListOf<String>()
    var webAuthResult: String = ""

    /** Профиль «пользователь уже вошёл в Google»: сохранённый аккаунт в local-хранилище. */
    fun seedAuthenticatedAccount(account: Account) {
        local["auth.account"] = AccountCodec.encode(account)
    }

    override suspend fun getSessionString(key: String): String? = session[key]
    override suspend fun setSessionString(key: String, value: String) { session[key] = value }
    override suspend fun removeSessionKey(key: String) { session.remove(key) }
    override suspend fun clearSession() = session.clear()

    override suspend fun getLocalString(key: String): String? = local[key]
    override suspend fun setLocalString(key: String, value: String) { local[key] = value }
    override suspend fun removeLocalKey(key: String) { local.remove(key) }
    override suspend fun clearLocal() = local.clear()

    override fun getRedirectUrl(path: String): String = "https://fake.chromiumapp.org/$path"
    override suspend fun launchWebAuthFlow(authUrl: String, interactive: Boolean): String = webAuthResult
    override fun openFullTab(relativePath: String?) { openedTabs += relativePath }
    override fun openExternalUrl(url: String) { openedExternalUrls += url }
    override suspend fun copyToClipboard(text: String) { clipboard += text }
}
