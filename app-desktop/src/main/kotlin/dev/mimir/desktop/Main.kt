package dev.mimir.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.mimir.core.BuildConfig
import dev.mimir.core.StubSyncEngine
import dev.mimir.core.label
import dev.mimir.desktop.tray.createTray
import dev.mimir.ui.MimirApp

fun main() = application {
    val engine = remember { StubSyncEngine() }
    val tray = remember { createTray() }
    var visible by remember { mutableStateOf(true) }

    remember {
        tray.show(engine.status.value.label(), onOpen = { visible = true }, onSyncNow = {}, onQuit = ::exitApplication)
    }

    Window(
        onCloseRequest = { if (tray.available) visible = false else exitApplication() },
        visible = visible,
        title = BuildConfig.APP_NAME,
    ) {
        MimirApp(engine)
    }
}
