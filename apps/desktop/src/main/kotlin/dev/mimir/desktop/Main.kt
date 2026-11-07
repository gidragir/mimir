package dev.mimir.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.application
import dev.mimir.core.BuildConfig
import dev.mimir.daemon.DaemonConfig
import dev.mimir.desktop.tray.PopoverSupport
import dev.mimir.desktop.tray.PopoverVisibilityController
import dev.mimir.desktop.tray.TrayClickAnchor
import dev.mimir.desktop.tray.TrayPopoverWindow
import dev.mimir.desktop.tray.bindUpdateItem
import dev.mimir.desktop.tray.createTray
import dev.mimir.desktop.tray.trayLabel
import dev.mimir.desktop.picker.DesktopObsidianVaultDiscovery
import dev.mimir.desktop.picker.defaultFolderPicker
import dev.mimir.desktop.platform.WindowsProtocolRegistrar
import dev.mimir.desktop.ipc.SingleInstanceManager
import dev.mimir.desktop.window.PlatformWindowStyler
import dev.mimir.ui.MimirApp
import dev.mimir.ui.picker.LocalFolderPicker
import dev.mimir.ui.platform.LocalVaultDiscovery
import dev.mimir.ui.model.MimirUiModel
import dev.mimir.ui.screens.StartupScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import javax.imageio.ImageIO
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val ipc = SingleInstanceManager()
    if (!ipc.acquirePrimary(args)) {
        exitProcess(0)
    }
    Thread.ofVirtual().name("mimir-proto-reg").start {
        WindowsProtocolRegistrar.registerIfWindows()
    }
    val config = try {
        DaemonConfig.load()
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(1)
    }
    runDesktop(config, ipc)
    // Демон и D-Bus держат потоки; backend уже закрыт в onDispose.
    exitProcess(0)
}

private fun runDesktop(config: DaemonConfig, ipc: SingleInstanceManager) = application {
    val scope = rememberCoroutineScope()
    val tray = remember { createTray() }
    val backendRef = remember { AtomicReference<DesktopBackend?>() }
    var model by remember { mutableStateOf<MimirUiModel?>(null) }
    var visible by remember { mutableStateOf(true) }
    var startupError by remember { mutableStateOf<String?>(null) }
    val popoverController = remember { PopoverVisibilityController() }
    val popoverSupported = remember { PopoverSupport.isSupported() }
    var popoverVisible by remember { mutableStateOf(false) }
    var popoverAnchor by remember { mutableStateOf<TrayClickAnchor?>(null) }
    val mainWindowRef = remember { AtomicReference<java.awt.Window?>(null) }
    val openMainWindow: () -> Unit = {
        scope.launch(Dispatchers.Main) {
            popoverController.dismiss()
            popoverVisible = false
            visible = true
            mainWindowRef.get()?.let(PlatformFocus::activate)
        }
    }

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
                    onOpen = openMainWindow,
                    onSyncNow = { scope.launch { runCatching { ready.triggerSync() } } },
                    onQuit = ::exitApplication,
                    onActivate = { anchor ->
                        // niri (xwayland-satellite) не показывает самостоятельный поповер: клик открывает главное окно
                        if (!popoverSupported) {
                            openMainWindow()
                        } else {
                            scope.launch(Dispatchers.Main) {
                                popoverVisible = popoverController.onTrayActivate(anchor)
                                popoverAnchor = popoverController.lastAnchor
                            }
                        }
                    },
                )
            }
            launch { bindUpdateItem(tray, ready.updateState, openMainWindow) }
            combine(ready.status, ready.progress) { s, p -> trayLabel(s, p) }
                .distinctUntilChanged()
                .collect { tray.update(it) }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            tray.close()
            backendRef.get()?.close()
            ipc.close()
        }
    }

    val browserOpener = remember { SystemBrowserOpener() }
    val vaultDiscovery = remember { DesktopObsidianVaultDiscovery() }
    // Иконка окна для заголовка и панели задач; без неё Windows и Linux показывают значок Java.
    val windowIcon = remember {
        SingleInstanceManager::class.java.getResourceAsStream("/icon.png")
            ?.use { BitmapPainter(ImageIO.read(it).toComposeImageBitmap()) }
    }

    Window(
        // Без трея окно — единственный способ достучаться до приложения, поэтому закрытие завершает его.
        onCloseRequest = { if (tray.available) visible = false else exitApplication() },
        visible = visible,
        title = BuildConfig.APP_NAME,
        icon = windowIcon,
        state = rememberWindowState(size = DpSize(960.dp, 680.dp)),
    ) {
        val isDark = isSystemInDarkTheme()
        LaunchedEffect(window, isDark) {
            mainWindowRef.set(window)
            PlatformWindowStyler.apply(window, isDark)
        }
        LaunchedEffect(window) {
            window.minimumSize = java.awt.Dimension(720, 520)
            ipc.onActivationRequest = {
                scope.launch(Dispatchers.Main) {
                    visible = true
                    PlatformFocus.activate(window)
                }
            }
        }
        val folderPicker = remember(window) { defaultFolderPicker(parentFrame = { window }) }
        val current = model
        if (current != null) {
            CompositionLocalProvider(
                LocalFolderPicker provides folderPicker,
                LocalVaultDiscovery provides vaultDiscovery,
                LocalUriHandler provides browserOpener,
            ) { MimirApp(current) }
        } else {
            StartupScreen(startupError?.let { "Не удалось запустить Mimir: $it" } ?: "Запуск…")
        }
    }

    TrayPopoverWindow(
        visible = popoverVisible,
        anchor = popoverAnchor,
        model = model,
        startupError = startupError,
        onFocusLost = { popoverVisible = popoverController.onFocusLost() },
        onDismissRequest = {
            popoverController.dismiss()
            popoverVisible = false
        },
        onOpenMainWindow = openMainWindow,
        onQuit = ::exitApplication,
    )
}
