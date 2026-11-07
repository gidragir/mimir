package dev.mimir.desktop.tray

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PopoverSupportTest {
    @Test
    fun `niri session has no popover - xwayland-satellite shows override-redirect windows only as child popups`() {
        assertFalse(PopoverSupport.isSupported("Linux", mapOf("NIRI_SOCKET" to "/run/user/1000/niri.sock")))
        assertFalse(PopoverSupport.isSupported("Linux", mapOf("XDG_CURRENT_DESKTOP" to "niri")))
    }

    @Test
    fun `other linux desktops keep the popover`() {
        assertTrue(PopoverSupport.isSupported("Linux", mapOf("XDG_CURRENT_DESKTOP" to "KDE")))
        assertTrue(PopoverSupport.isSupported("Linux", emptyMap()))
    }

    @Test
    fun `windows and macos keep the popover even with stray niri variables`() {
        assertTrue(PopoverSupport.isSupported("Windows 11", mapOf("XDG_CURRENT_DESKTOP" to "niri")))
        assertTrue(PopoverSupport.isSupported("Mac OS X", emptyMap()))
    }
}
