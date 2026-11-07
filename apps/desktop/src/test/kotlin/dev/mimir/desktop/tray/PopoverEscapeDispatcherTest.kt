package dev.mimir.desktop.tray

import java.awt.Canvas
import java.awt.Component
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Esc в поповере: на Windows фокус после показа остаётся на самом ComposeWindow, а не на компоненте Compose,
 * поэтому onPreviewKeyEvent окна не вызывался (проверено в VM win11). Esc перехватывается на уровне AWT.
 */
class PopoverEscapeDispatcherTest {
    private val popover = Canvas()
    private val other = Canvas()
    private var dismissed = 0
    private val dispatcher = PopoverEscapeDispatcher(owns = { it === popover }, onEscape = { dismissed++ })

    private fun key(source: Component, id: Int, code: Int) =
        KeyEvent(source, id, 0L, 0, code, KeyEvent.CHAR_UNDEFINED)

    @Test
    fun escapePressedInPopoverDismissesAndConsumes() {
        assertTrue(dispatcher.dispatchKeyEvent(key(popover, KeyEvent.KEY_PRESSED, KeyEvent.VK_ESCAPE)))
        assertEquals(1, dismissed)
    }

    @Test
    fun otherKeysAndReleaseAreIgnored() {
        assertFalse(dispatcher.dispatchKeyEvent(key(popover, KeyEvent.KEY_PRESSED, KeyEvent.VK_A)))
        assertFalse(dispatcher.dispatchKeyEvent(key(popover, KeyEvent.KEY_RELEASED, KeyEvent.VK_ESCAPE)))
        assertEquals(0, dismissed)
    }

    @Test
    fun escapeInAnotherWindowIsIgnored() {
        assertFalse(dispatcher.dispatchKeyEvent(key(other, KeyEvent.KEY_PRESSED, KeyEvent.VK_ESCAPE)))
        assertEquals(0, dismissed)
    }
}
