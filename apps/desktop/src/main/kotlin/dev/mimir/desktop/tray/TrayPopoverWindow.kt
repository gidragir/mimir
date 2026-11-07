package dev.mimir.desktop.tray

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.SwingWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.WindowDecoration
import androidx.compose.ui.window.rememberWindowState
import dev.mimir.core.BuildConfig
import dev.mimir.ui.MimirTheme
import dev.mimir.ui.MimirTokens
import dev.mimir.ui.model.MimirUiModel
import dev.mimir.ui.screens.TrayPopoverScreen
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.MouseInfo
import java.awt.Toolkit
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities
import kotlin.math.ceil

// Высота до первого измерения содержимого; дальше окно подгоняется под карточку (ScreenPositionCalculator.fitHeight).
private val POPOVER_INITIAL_SIZE = DpSize(320.dp, 300.dp)

/**
 * Безрамочное окно-поповер у иконки трея. Тип окна — [PopoverBehavior.windowType]: `POPUP` тайлинг-WM не раскладывает,
 * на niri — обычное окно фиксированного размера, которое niri делает плавающим ([PopoverBehavior]).
 * Пока [visible] == false, содержимое не композируется — фоновой нагрузки нет.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ApplicationScope.TrayPopoverWindow(
    visible: Boolean,
    anchor: TrayClickAnchor?,
    behavior: PopoverBehavior,
    model: MimirUiModel?,
    startupError: String?,
    onFocusLost: () -> Unit,
    onDismissRequest: () -> Unit,
    onOpenMainWindow: () -> Unit,
    onQuit: () -> Unit,
) {
    val focusLost by rememberUpdatedState(onFocusLost)
    val dismiss by rememberUpdatedState(onDismissRequest)
    SwingWindow(
        onCloseRequest = onDismissRequest,
        visible = visible,
        decoration = WindowDecoration.Undecorated(),
        resizable = false,
        alwaysOnTop = true,
        focusable = true,
        state = rememberWindowState(size = POPOVER_INITIAL_SIZE),
        title = BuildConfig.APP_NAME,
        // Тип окна меняется только до displayable: init вызывается сразу после конструктора ComposeWindow.
        // Из DisposableEffect присваивание падало (IllegalComponentStateException) и окно оставалось NORMAL.
        init = { it.type = behavior.windowType },
    ) {
        DisposableEffect(window) {
            val listener = object : WindowAdapter() {
                override fun windowLostFocus(e: WindowEvent) = focusLost()
            }
            window.addWindowFocusListener(listener)
            val escape = PopoverEscapeDispatcher(
                owns = { it === window || SwingUtilities.getWindowAncestor(it) === window },
                onEscape = { dismiss() },
            )
            val kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            kfm.addKeyEventDispatcher(escape)
            onDispose {
                kfm.removeKeyEventDispatcher(escape)
                window.removeWindowFocusListener(listener)
            }
        }
        // Высота карточки в dp (= логические единицы AWT); переживает скрытие, чтобы повторный показ был сразу нужного размера.
        var contentHeight by remember { mutableStateOf<Dp?>(null) }
        LaunchedEffect(visible, anchor, contentHeight) {
            if (visible) {
                val screen = screenFor(anchor)
                val geometry = ScreenGeometry(screen.bounds, Toolkit.getDefaultToolkit().getScreenInsets(screen))
                contentHeight?.let { window.setSize(window.width, ScreenPositionCalculator.fitHeight(ceil(it.value).toInt(), geometry)) }
                val point = ScreenPositionCalculator.calculate(screen = geometry, popoverSize = window.size, anchor = anchor)
                window.setLocation(point.x, point.y)
            }
        }
        LaunchedEffect(visible) {
            if (visible) {
                window.toFront()
                window.requestFocus()
            }
        }
        if (visible) {
            val density = LocalDensity.current
            MimirTheme {
                // verticalScroll даёт карточке неограниченную высоту: она меряется целиком, а на низком экране прокручивается.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(if (isSystemInDarkTheme()) MimirTokens.DarkSurface else MimirTokens.LightSurface)
                        .verticalScroll(rememberScrollState()),
                ) {
                    TrayPopoverScreen(
                        model = model,
                        startupError = startupError,
                        onOpenMainWindow = onOpenMainWindow,
                        onQuit = onQuit,
                        modifier = Modifier.onSizeChanged { contentHeight = with(density) { it.height.toDp() } },
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
