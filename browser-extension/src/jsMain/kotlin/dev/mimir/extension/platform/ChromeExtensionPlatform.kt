package dev.mimir.extension.platform

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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

    override fun observeSessionKey(key: String, onChanged: (String?) -> Unit) {
        chrome.storage.onChanged.addListener { changes: dynamic, area: String ->
            if (area == "session" && changes[key] != undefined) onChanged(changes[key].newValue as? String)
        }
    }

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

    override suspend fun launchLoopbackAuthFlow(authUrl: String, redirectPrefix: String): String =
        suspendCancellableCoroutine { continuation ->
            var tabId = -1
            var onUpdated: dynamic = null
            var onRemoved: dynamic = null
            fun finish() {
                chrome.tabs.onUpdated.removeListener(onUpdated)
                chrome.tabs.onRemoved.removeListener(onRemoved)
            }
            onUpdated = { id: Int, change: dynamic, _: dynamic ->
                val url = change.url as? String
                if (id == tabId && url != null && url.startsWith(redirectPrefix) && continuation.isActive) {
                    finish()
                    chrome.tabs.remove(id)
                    continuation.resume(url)
                }
            }
            onRemoved = { id: Int, _: dynamic ->
                if (id == tabId && continuation.isActive) {
                    finish()
                    continuation.resumeWithException(IllegalStateException("Окно входа закрыто без результата"))
                }
            }
            chrome.tabs.onUpdated.addListener(onUpdated)
            chrome.tabs.onRemoved.addListener(onRemoved)
            (chrome.tabs.create(json("url" to authUrl)) as Promise<dynamic>).then { tab: dynamic -> tabId = tab.id as Int }
            continuation.invokeOnCancellation { finish() }
        }

    override fun openFullTab(relativePath: String?) {
        val url = chrome.runtime.getURL(fullTabPageUrl(relativePath)) as String
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

/**
 * Расшифрованный путь заметки не кладём в URL: адрес вкладки попадает в историю и файлы восстановления
 * сессии на диске. Открытая заметка передаётся через `session.selectedPath` (`chrome.storage.session`).
 */
@Suppress("UNUSED_PARAMETER")
internal fun fullTabPageUrl(relativePath: String?): String = "popup.html?mode=tab"
