package dev.mimir.desktop.tray

import java.awt.Window
import kotlin.test.Test
import kotlin.test.assertEquals

class PopoverBehaviorTest {
    @Test
    fun `niri gets a normal fixed-size window that niri floats and stays open on focus loss`() {
        // xwayland-satellite показывает POPUP только как xdg_popup сфокусированного X11-окна приложения
        val expected = PopoverBehavior(Window.Type.NORMAL, closesOnFocusLoss = false)
        assertEquals(expected, PopoverBehavior.detect("Linux", mapOf("NIRI_SOCKET" to "/run/user/1000/niri.sock")))
        assertEquals(expected, PopoverBehavior.detect("Linux", mapOf("XDG_CURRENT_DESKTOP" to "niri")))
    }

    @Test
    fun `other platforms keep the override-redirect popup closed on focus loss`() {
        val popup = PopoverBehavior(Window.Type.POPUP, closesOnFocusLoss = true)
        assertEquals(popup, PopoverBehavior.detect("Linux", mapOf("XDG_CURRENT_DESKTOP" to "KDE")))
        assertEquals(popup, PopoverBehavior.detect("Linux", emptyMap()))
        assertEquals(popup, PopoverBehavior.detect("Windows 11", mapOf("XDG_CURRENT_DESKTOP" to "niri")))
        assertEquals(popup, PopoverBehavior.detect("Mac OS X", emptyMap()))
    }
}
