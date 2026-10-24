package dev.mimir.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.application
import dev.mimir.core.BuildConfig
import dev.mimir.daemon.DaemonConfig
import dev.mimir.desktop.tray.createTray
import dev.mimir.desktop.tray.trayLabel
import dev.mimir.desktop.picker.defaultFolderPicker
import dev.mimir.ui.MimirApp
import dev.mimir.ui.picker.LocalFolderPicker
import dev.mimir.ui.model.MimirUiModel
import dev.mimir.ui.screens.StartupScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

fun main() {
    val config = try {
        DaemonConfig.load()
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(1)
    }
    runDesktop(config)
    // Демон и D-Bus держат потоки; backend уже закрыт в onDispose.
    exitProcess(0)
}

private fun runDesktop(config: DaemonConfig) = application {
    val scope = rememberCoroutineScope()
    val tray = remember { createTray() }
    val backendRef = remember { AtomicReference<DesktopBackend?>() }
    var model by remember { mutableStateOf<MimirUiModel?>(null) }
    var visible by remember { mutableStateOf(true) }
    var startupError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val backend = DaemonServiceFactory.create(config)
            backendRef.set(backend)
            val configDir = config.configDir?.let { Paths.get(it) }
            val remember = if (backend.mode == DesktopBackend.Mode.RemoteDaemon || configDir == null) {
                null
            } else {
                { password: String -> rememberPassword(configDir, password) }
            }
            model = DaemonServiceUiModel(backend.service, scope, rememberPassword = remember)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            startupError = e.message ?: e::class.simpleName
        }
    }

    val ready = model
    if (ready != null) {
        LaunchedEffect(ready) {
            withContext(Dispatchers.Default) {
                tray.show(
                    trayLabel(ready.status.value, ready.progress.value),
                    onOpen = { visible = true },
                    onSyncNow = { scope.launch { runCatching { ready.triggerSync() } } },
                    onQuit = ::exitApplication,
                )
            }
            combine(ready.status, ready.progress) { s, p -> trayLabel(s, p) }
                .distinctUntilChanged()
                .collect { tray.update(it) }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            tray.close()
            backendRef.get()?.close()
        }
    }

    val folderPicker = remember { defaultFolderPicker() }

    Window(
        // Без трея окно — единственный способ достучаться до приложения, поэтому закрытие завершает его.
        onCloseRequest = { if (tray.available) visible = false else exitApplication() },
        visible = visible,
        title = BuildConfig.APP_NAME,
        state = rememberWindowState(size = DpSize(960.dp, 680.dp)),
    ) {
        LaunchedEffect(Unit) { window.minimumSize = java.awt.Dimension(720, 520) }
        val current = model
        if (current != null) {
            CompositionLocalProvider(LocalFolderPicker provides folderPicker) { MimirApp(current) }
        } else {
            StartupScreen(startupError?.let { "Не удалось запустить Mimir: $it" } ?: "Запуск…")
        }
    }
}
