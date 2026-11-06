package dev.mimir.desktop.window

import androidx.compose.ui.graphics.Color
import dev.mimir.ui.MimirTokens
import javax.swing.JPanel
import javax.swing.JRootPane
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformWindowStylerTest {
    @Test
    fun `toWin32ColorRef converts Compose RGB to Win32 BGR COLORREF format`() {
        // DarkSurface = #262626 -> 0x00262626
        assertEquals(0x00262626, PlatformWindowStyler.toWin32ColorRef(MimirTokens.DarkSurface))
        // LightSurface = #F8F8F8 -> 0x00F8F8F8
        assertEquals(0x00F8F8F8, PlatformWindowStyler.toWin32ColorRef(MimirTokens.LightSurface))
        // DarkText = #DCDDDE (R=0xDC, G=0xDD, B=0xDE) -> BGR = 0x00DEDDDC
        assertEquals(0x00DEDDDC, PlatformWindowStyler.toWin32ColorRef(MimirTokens.DarkText))
        // Accent = #705DCF (R=0x70, G=0x5D, B=0xCF) -> BGR = 0x00CF5D70
        assertEquals(0x00CF5D70, PlatformWindowStyler.toWin32ColorRef(MimirTokens.Accent))
        // Pure red (R=0xFF, G=0x00, B=0x00) -> BGR = 0x000000FF
        assertEquals(0x000000FF, PlatformWindowStyler.toWin32ColorRef(Color(0xFFFF0000)))
    }

    @Test
    fun `applyMacRootPaneProperties configures dark and light appearance and transparent titlebar`() {
        val rootPane = JRootPane()
        PlatformWindowStyler.applyMacRootPaneProperties(rootPane, isDark = true)
        assertEquals("NSAppearanceNameDarkAqua", rootPane.getClientProperty("apple.awt.application.appearance"))
        assertEquals(true, rootPane.getClientProperty("apple.awt.fullWindowContent"))
        assertEquals(true, rootPane.getClientProperty("apple.awt.transparentTitleBar"))
        assertEquals(false, rootPane.getClientProperty("apple.awt.windowTitleVisible"))

        PlatformWindowStyler.applyMacRootPaneProperties(rootPane, isDark = false)
        assertEquals("NSAppearanceNameAqua", rootPane.getClientProperty("apple.awt.application.appearance"))
    }
}
