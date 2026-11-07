package dev.mimir.desktop.tray

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PopoverVisibilityControllerTest {
    @Test
    fun `toggles visibility on tray activate when no recent focus loss`() {
        var now = 1_000L
        val controller = PopoverVisibilityController(debounceMs = 200L, clockMillis = { now })
        val anchor = TrayClickAnchor(1800, 1040)

        assertTrue(controller.onTrayActivate(anchor))
        assertTrue(controller.visible)
        assertEquals(anchor, controller.lastAnchor)

        assertFalse(controller.onTrayActivate(anchor))
        assertFalse(controller.visible)
    }

    @Test
    fun `suppresses immediate reopen within debounce window after focus loss`() {
        var now = 5_000L
        val controller = PopoverVisibilityController(debounceMs = 200L, clockMillis = { now })

        assertTrue(controller.onTrayActivate(null))
        now = 5_100L
        assertFalse(controller.onFocusLost())
        assertFalse(controller.visible)

        // Через 50 мс после потери фокуса приходит клик по иконке трея — должен быть подавлен
        now = 5_150L
        assertFalse(controller.onTrayActivate(null))
        assertFalse(controller.visible)

        // После истечения 200 мс — снова открывает поповер
        now = 5_301L
        assertTrue(controller.onTrayActivate(null))
        assertTrue(controller.visible)
    }

    @Test
    fun `dismiss hides popover without triggering focus loss debounce`() {
        var now = 2_000L
        val controller = PopoverVisibilityController(debounceMs = 200L, clockMillis = { now })
        controller.onTrayActivate(null)
        controller.dismiss()
        assertFalse(controller.visible)

        // Повторный клик сразу после явного dismiss() открывает поповер без задержки
        now = 2_010L
        assertTrue(controller.onTrayActivate(null))
    }

    @Test
    fun `focus loss keeps the popover open when the platform says so`() {
        // niri: фокус уходит через ~200 мс после показа и меняется, пока курсор идёт к окну (focus-follows-mouse)
        val controller = PopoverVisibilityController(debounceMs = 200L, closesOnFocusLoss = false, clockMillis = { 1_000L })
        assertTrue(controller.onTrayActivate(null))
        assertTrue(controller.onFocusLost())
        assertTrue(controller.visible)
        assertFalse(controller.onTrayActivate(null))
    }
}
