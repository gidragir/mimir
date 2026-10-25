package dev.mimir.desktop

import androidx.compose.ui.platform.UriHandler
import java.awt.Desktop
import java.net.URI

/**
 * Открывает ссылку в браузере по умолчанию: на Linux через `xdg-open` (в уже запущенном браузере — новая вкладка),
 * иначе или если `xdg-open` недоступен — через AWT Desktop.
 */
class SystemBrowserOpener(
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val launch: (List<String>) -> Unit = ::startDetached,
    private val browse: (String) -> Unit = { Desktop.getDesktop().browse(URI(it)) },
) : UriHandler {
    override fun openUri(uri: String) {
        require(uri.startsWith("https://") || uri.startsWith("http://")) { "Only web links can be opened" }
        if (osName.contains("linux", ignoreCase = true)) {
            try {
                launch(listOf("xdg-open", uri))
                return
            } catch (_: java.io.IOException) {
                // xdg-open не установлен — пробуем AWT
            }
        }
        browse(uri)
    }
}

private fun startDetached(command: List<String>) {
    ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
}
