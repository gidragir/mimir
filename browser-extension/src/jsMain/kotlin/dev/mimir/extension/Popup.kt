package dev.mimir.extension

import dev.mimir.core.BuildConfig
import dev.mimir.core.NotImplementedCipher
import kotlinx.browser.document

fun main() {
    document.getElementById("title")?.textContent = "${BuildConfig.APP_NAME} Quick View"
    document.getElementById("message")?.textContent = decryptionMessage()
}

// Код шифрования из core собирается в расширение; пока он заглушка
private fun decryptionMessage(): String =
    try {
        NotImplementedCipher.decryptName("")
        ""
    } catch (_: NotImplementedError) {
        "Расшифровка ещё не реализована"
    }
