package dev.mimir.web

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import dev.mimir.client.HttpDaemonClient
import dev.mimir.ui.MimirApp
import dev.mimir.ui.model.DaemonServiceUiModel
import kotlinx.browser.window
import org.w3c.dom.url.URLSearchParams
import kotlin.js.ExperimentalWasmJsInterop

private const val TOKEN_STORAGE_KEY = "mimir_token"
private const val TOKEN_QUERY_PARAM = "token"

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport {
        val scope = rememberCoroutineScope()
        val client = remember(scope) {
            HttpDaemonClient(
                baseUrl = resolveBaseUrl(),
                token = resolveSessionToken(),
                scope = scope,
            )
        }
        val model = remember(client, scope) { DaemonServiceUiModel(client, scope) }
        DisposableEffect(client) {
            onDispose { client.close() }
        }
        MimirApp(model = model)
    }
}

private fun resolveBaseUrl(): String =
    window.location.origin
        .takeIf { it.isNotBlank() && it != "null" }
        ?.trimEnd('/')
        ?: ""

@OptIn(ExperimentalWasmJsInterop::class)
private fun resolveSessionToken(): String? {
    val params = URLSearchParams(window.location.search.toJsString())
    val queryToken = params.get(TOKEN_QUERY_PARAM)?.trim()?.takeIf { it.isNotEmpty() }
    if (queryToken != null) {
        runCatching { window.sessionStorage.setItem(TOKEN_STORAGE_KEY, queryToken) }
        scrubTokenQueryParam(params)
        return queryToken
    }
    return runCatching { window.sessionStorage.getItem(TOKEN_STORAGE_KEY) }
        .getOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

@OptIn(ExperimentalWasmJsInterop::class)
private fun scrubTokenQueryParam(params: URLSearchParams) {
    params.delete(TOKEN_QUERY_PARAM)
    val remaining = params.toString()
    val cleanUrl = buildString {
        append(window.location.pathname)
        if (remaining.isNotEmpty()) {
            append('?')
            append(remaining)
        }
        append(window.location.hash)
    }
    runCatching { window.history.replaceState(null, "", cleanUrl) }
}
