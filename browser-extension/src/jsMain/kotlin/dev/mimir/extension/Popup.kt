package dev.mimir.extension

import dev.mimir.core.BuildConfig
import kotlinx.browser.document

fun main() {
    document.getElementById("title")?.textContent = "${BuildConfig.APP_NAME} Quick View"
    document.getElementById("message")?.textContent = decryptionMessage()
}

// Пароль хранится в SecretStore только с этапа 2; до него шифр не создаётся
private fun decryptionMessage(): String = "Расшифровка недоступна: нет пароля"
