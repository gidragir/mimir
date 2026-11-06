package dev.mimir.desktop.tray

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import dev.mimir.ui.MimirTheme
import dev.mimir.ui.MimirTokens
import dev.mimir.ui.model.MimirUiModel
import dev.mimir.ui.screens.TrayPopoverScreen
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Toolkit
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

private val POPOVER_SIZE = DpSize(320.dp, 400.dp)

/**
 * Безрамочное окно-поповер у иконки трея (`Window.Type.POPUP`: тайлинг-WM его не раскладывает).
 * Пока [visible] == false, содержимое не композируется — фоновой нагрузки нет.
 */
@Composable
fun ApplicationScope.TrayPopoverWindow(
    visible: Boolean,
    anchor: TrayClickAnchor?,
    model: MimirUiModel?,
    startupError: String?,
    onFocusLost: () -> Unit,
    onDismissRequest: () -> Unit,
    onOpenMainWindow: () -> Unit,
    onQuit: () -> Unit,
) {
    val focusLost by rememberUpdatedState(onFocusLost)
    Window(
        onCloseRequest = onDismissRequest,
        visible = visible,
        undecorated = true,
        resizable = false,
        alwaysOnTop = true,
        focusable = true,
        state = rememberWindowState(size = POPOVER_SIZE),
        onPreviewKeyEvent = {
            if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
                onDismissRequest()
                true
            } else {
                false
            }
        },
    ) {
        DisposableEffect(window) {
            // Тип можно менять только пока окно ещё не displayable.
            runCatching { window.type = java.awt.Window.Type.POPUP }
            val listener = object : WindowAdapter() {
                override fun windowLostFocus(e: WindowEvent) = focusLost()
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }
        LaunchedEffect(visible, anchor) {
            if (visible) {
                val screen = screenFor(anchor)
                val point = ScreenPositionCalculator.calculate(
                    screen = ScreenGeometry(screen.bounds, Toolkit.getDefaultToolkit().getScreenInsets(screen)),
                    popoverSize = window.size,
                    anchor = anchor,
                )
                window.setLocation(point.x, point.y)
                window.toFront()
                window.requestFocus()
            }
        }
        if (visible) {
            MimirTheme {
                Box(Modifier.fillMaxSize().background(if (isSystemInDarkTheme()) MimirTokens.DarkSurface else MimirTokens.LightSurface)) {
                    TrayPopoverScreen(
                        model = model,
                        startupError = startupError,
                        onOpenMainWindow = onOpenMainWindow,
                        onQuit = onQuit,
                    )
                }
            }
        }
    }
}

/** Монитор клика по иконке; без якоря — монитор курсора, затем основной. */
private fun screenFor(anchor: TrayClickAnchor?): GraphicsConfiguration {
    val devices = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
    val point = anchor?.let { java.awt.Point(it.screenX, it.screenY) } ?: MouseInfo.getPointerInfo()?.location
    val configs = devices.map { it.defaultConfiguration }
    return configs.firstOrNull { point != null && it.bounds.contains(point) }
        ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
}
