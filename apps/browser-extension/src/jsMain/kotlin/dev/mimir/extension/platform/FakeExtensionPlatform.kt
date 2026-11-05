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

    /** Что «вернёт» вкладка входа: по адресу авторизации строит итоговый loopback-URL. */
    var loopbackResponder: (authUrl: String) -> String = { error("loopbackResponder не задан") }
    val loopbackAuthUrls = mutableListOf<String>()

    /** Профиль «пользователь уже вошёл в Google»: сохранённый аккаунт в local-хранилище. */
    fun seedAuthenticatedAccount(account: Account) {
        local["auth.account"] = AccountCodec.encode(account)
    }

    private val observers = mutableListOf<Pair<String, (String?) -> Unit>>()

    private fun notifyObservers(key: String) = observers.filter { it.first == key }.forEach { it.second(session[key]) }

    override fun observeSessionKey(key: String, onChanged: (String?) -> Unit) { observers += key to onChanged }

    override suspend fun getSessionString(key: String): String? = session[key]
    override suspend fun setSessionString(key: String, value: String) { session[key] = value; notifyObservers(key) }
    override suspend fun removeSessionKey(key: String) { session.remove(key); notifyObservers(key) }
    override suspend fun clearSession() {
        val keys = session.keys.toList()
        session.clear()
        keys.forEach(::notifyObservers)
    }

    override suspend fun getLocalString(key: String): String? = local[key]
    override suspend fun setLocalString(key: String, value: String) { local[key] = value }
    override suspend fun removeLocalKey(key: String) { local.remove(key) }
    override suspend fun clearLocal() = local.clear()

    override fun getRedirectUrl(path: String): String = "https://fake.chromiumapp.org/$path"
    override suspend fun launchWebAuthFlow(authUrl: String, interactive: Boolean): String = webAuthResult
    override suspend fun launchLoopbackAuthFlow(authUrl: String, redirectPrefix: String): String {
        loopbackAuthUrls += authUrl
        return loopbackResponder(authUrl)
    }
    override fun openFullTab(relativePath: String?) { openedTabs += relativePath }
    override fun openExternalUrl(url: String) { openedExternalUrls += url }
    override suspend fun copyToClipboard(text: String) { clipboard += text }

    var badgeText: String = ""
        private set
    override fun setBadge(text: String) { badgeText = text }
}
