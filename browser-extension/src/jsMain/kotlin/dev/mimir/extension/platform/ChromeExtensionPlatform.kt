package dev.mimir.extension.platform

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLTextAreaElement
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
    override suspend fun removeSessionKey(key: String) {
        awaitVoid(session.remove(key))
    }
    override suspend fun clearSession() {
        awaitVoid(session.clear())
    }

    override suspend fun getLocalString(key: String): String? = readString(local, key)
    override suspend fun setLocalString(key: String, value: String) = writeString(local, key, value)
    override suspend fun removeLocalKey(key: String) {
        awaitVoid(local.remove(key))
    }
    override suspend fun clearLocal() {
        awaitVoid(local.clear())
    }

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
        try {
            awaitVoid(window.navigator.asDynamic().clipboard.writeText(text))
        } catch (e: Throwable) {
            // Clipboard API в боковой панели бывает недоступен (фокус, политика): копируем через выделение
            if (!copyViaSelection(text)) throw e
        }
    }

    private fun copyViaSelection(text: String): Boolean {
        val area = document.createElement("textarea") as HTMLTextAreaElement
        area.value = text
        area.setAttribute("readonly", "")
        area.style.position = "fixed"
        area.style.opacity = "0"
        document.body!!.appendChild(area)
        area.select()
        val copied = try {
            document.asDynamic().execCommand("copy") as Boolean
        } catch (e: Throwable) {
            false
        }
        area.remove()
        return copied
    }

    /** `await()` над `Promise<dynamic>` возвращает dynamic: вызывать на нём `.let` нельзя, поэтому результат отбрасываем здесь. */
    private suspend fun awaitVoid(promise: dynamic) {
        (promise as Promise<dynamic>).await()
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
