package dev.mimir.desktop.tray

import java.awt.Component
import java.awt.KeyEventDispatcher
import java.awt.event.KeyEvent

/**
 * Esc закрывает поповер независимо от того, какой компонент окна держит фокус. После `toFront()` на Windows
 * фокус-владельцем остаётся сам `ComposeWindow`, и `onPreviewKeyEvent` окна Compose не вызывается, поэтому
 * Esc перехватывается на уровне AWT ([java.awt.KeyboardFocusManager.addKeyEventDispatcher]).
 */
class PopoverEscapeDispatcher(
    private val owns: (Component) -> Boolean,
    private val onEscape: () -> Unit,
) : KeyEventDispatcher {
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.VK_ESCAPE) return false
        if (e.id != KeyEvent.KEY_PRESSED && e.id != KeyEvent.KEY_RELEASED) return false
        val source = e.source as? Component ?: return false
        if (!owns(source)) return false
        // Закрываем по отпусканию: на niri (Xwayland) Esc, закрывший прошлый поповер, остаётся «нажатым» — его
        // отпускание получило другое окно — и повторяется нажатием, когда новый поповер получает фокус.
        if (e.id == KeyEvent.KEY_RELEASED) onEscape()
        return true
    }
}
