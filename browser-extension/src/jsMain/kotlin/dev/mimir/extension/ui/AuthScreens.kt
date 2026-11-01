package dev.mimir.extension.ui

import dev.mimir.core.Account
import dev.mimir.extension.platform.ExtensionPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement

/** Экраны до разблокировки: вход, мастер-пароль, прогресс. */
internal class AuthScreens(
    private val dom: DomKit,
    private val controller: ExtensionController,
    private val platform: ExtensionPlatform,
    private val scope: CoroutineScope,
) {
    fun loading(message: String, details: String? = null): HTMLElement =
        dom.el("section", "screen center").also { screen ->
            screen.appendChild(dom.el("div", "spinner"))
            screen.appendChild(dom.el("p", "muted", message))
            if (details != null) screen.appendChild(dom.el("p", "muted small", details))
        }

    fun authRequired(state: ExtensionUiState.AuthRequired): HTMLElement {
        val screen = dom.el("section", "screen center", testId = "auth-screen")
        screen.appendChild(dom.el("h1", null, "Mimir Quick View"))
        screen.appendChild(dom.el("p", "muted", "Быстрый просмотр зашифрованных заметок в браузере без скачивания на ПК"))
        state.error?.let { screen.appendChild(dom.el("p", "error", it, "auth-error")) }
        val prompt = state.deviceCodePrompt
        if (prompt != null) {
            screen.appendChild(dom.el("p", "muted", "Откройте страницу и введите код:"))
            screen.appendChild(dom.el("div", "device-code", prompt.userCode, "device-code"))
            screen.appendChild(dom.button("Открыть ${prompt.verificationUrl}", "btn primary") {
                platform.openExternalUrl(prompt.verificationUrl)
            })
        }
        if (state.isAuthorizing) {
            screen.appendChild(dom.el("div", "spinner"))
            screen.appendChild(dom.el("p", "muted small", "Ожидание входа…"))
        } else {
            screen.appendChild(dom.button("Войти через Google", "btn primary", "google-sign-in-btn") {
                scope.launch { controller.signInWithWebAuthFlow() }
            })
            screen.appendChild(dom.button("Войти по коду устройства", "btn link", "device-code-btn") {
                scope.launch { controller.startDeviceCodeFlow() }
            })
        }
        return screen
    }

    fun unlockRequired(state: ExtensionUiState.UnlockRequired): HTMLElement {
        val screen = dom.el("section", "screen center", testId = "unlock-screen")
        screen.appendChild(dom.el("h1", null, "Разблокировка хранилища"))
        screen.appendChild(accountRow(state.account))
        val password = dom.input("password", "Мастер-пароль", "password-input")
        password.setAttribute("autocomplete", "off")
        password.disabled = state.isVerifying
        val submit = { scope.launch { controller.unlock(password.value) }; Unit }
        password.onkeydown = { event -> if (event.key == "Enter") submit() }
        val toggle = dom.button("👁", "btn icon", title = "Показать пароль") {
            password.type = if (password.type == "password") "text" else "password"
        }
        screen.appendChild(dom.el("div", "password-row").also { it.appendChild(password); it.appendChild(toggle) })
        state.error?.let { screen.appendChild(dom.el("p", "error", it, "unlock-error")) }
        if (state.isVerifying) {
            screen.appendChild(dom.el("div", "spinner"))
            screen.appendChild(dom.el("p", "muted small", "Проверка пароля…"))
        } else {
            screen.appendChild(dom.button("Разблокировать", "btn primary", "unlock-btn") { submit() })
        }
        screen.appendChild(dom.el("p", "muted small", "Ключ хранится только в оперативной памяти браузера до его закрытия или блокировки."))
        return screen
    }

    private fun accountRow(account: Account): HTMLElement =
        dom.el("div", "account-row").also { row ->
            row.appendChild(dom.el("span", "muted", account.email.ifEmpty { account.displayName.orEmpty() }))
            row.appendChild(dom.button("Сменить", "btn link", "switch-account-btn") { scope.launch { controller.signOut() } })
        }
}
