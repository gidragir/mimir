package dev.mimir.extension.platform

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlin.js.Promise
import kotlin.js.json

private external val chrome: dynamic

/** Боевая реализация [ExtensionPlatform] поверх Chromium WebExtensions API (Manifest V3, промис-интерфейс). */
class ChromeExtensionPlatform : ExtensionPlatform {
    init {
        enableSidePanelOnActionClick()
    }

    private val session: dynamic get() = chrome.storage.session
    private val local: dynamic get() = chrome.storage.local

    override suspend fun getSessionString(key: String): String? = readString(session, key)
    override suspend fun setSessionString(key: String, value: String) = writeString(session, key, value)
    override suspend fun removeSessionKey(key: String) = (session.remove(key) as Promise<dynamic>).await().let { }
    override suspend fun clearSession() = (session.clear() as Promise<dynamic>).await().let { }

    override suspend fun getLocalString(key: String): String? = readString(local, key)
    override suspend fun setLocalString(key: String, value: String) = writeString(local, key, value)
    override suspend fun removeLocalKey(key: String) = (local.remove(key) as Promise<dynamic>).await().let { }
    override suspend fun clearLocal() = (local.clear() as Promise<dynamic>).await().let { }

    override fun getRedirectUrl(path: String): String = chrome.identity.getRedirectURL(path) as String

    override suspend fun launchWebAuthFlow(authUrl: String, interactive: Boolean): String {
        val details = json("url" to authUrl, "interactive" to interactive)
        val result = (chrome.identity.launchWebAuthFlow(details) as Promise<dynamic>).await()
        return result as? String ?: error("Окно входа закрыто без результата")
    }

    override fun openFullTab(relativePath: String?) {
        val query = relativePath?.let { "&path=${js("encodeURIComponent")(it)}" }.orEmpty()
        val url = chrome.runtime.getURL("popup.html?mode=tab$query") as String
        chrome.tabs.create(json("url" to url))
    }

    override fun openExternalUrl(url: String) {
        chrome.tabs.create(json("url" to url))
    }

    override suspend fun copyToClipboard(text: String) {
        (window.navigator.asDynamic().clipboard.writeText(text) as Promise<dynamic>).await()
    }

    private suspend fun readString(area: dynamic, key: String): String? {
        val result = (area.get(key) as Promise<dynamic>).await()
        return result[key] as? String
    }

    private suspend fun writeString(area: dynamic, key: String, value: String) {
        val item = js("({})")
        item[key] = value
        (area.set(item) as Promise<dynamic>).await()
    }

    /** Клик по иконке открывает боковую панель; в форках Chromium без `sidePanel` остаётся обычный popup. */
    private fun enableSidePanelOnActionClick() {
        try {
            val sidePanel = chrome.sidePanel
            if (sidePanel != undefined) sidePanel.setPanelBehavior(json("openPanelOnActionClick" to true))
        } catch (e: Throwable) {
            // нет поддержки sidePanel — работаем как popup
        }
    }
}
