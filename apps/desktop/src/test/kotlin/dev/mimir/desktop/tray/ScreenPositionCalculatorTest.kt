package dev.mimir.desktop.tray

import java.awt.Dimension
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenPositionCalculatorTest {
    private val popoverSize = Dimension(320, 380)

    @Test
    fun `windows bottom taskbar places popover at bottom right above taskbar`() {
        val screen = ScreenGeometry(
            bounds = Rectangle(0, 0, 1920, 1080),
            insets = Insets(0, 0, 48, 0),
        )
        val pos = ScreenPositionCalculator.calculate(screen, popoverSize, anchor = null, margin = 8, osName = "Windows 11")
        assertEquals(Point(1920 - 320 - 8, 1080 - 48 - 380 - 8), pos)
    }

    @Test
    fun `macos top menubar places popover at top right below menubar`() {
        val screen = ScreenGeometry(
            bounds = Rectangle(0, 0, 1440, 900),
            insets = Insets(32, 0, 64, 0),
        )
        val pos = ScreenPositionCalculator.calculate(screen, popoverSize, anchor = null, margin = 8, osName = "Mac OS X")
        assertEquals(Point(1440 - 320 - 8, 32 + 8), pos)
    }

    @Test
    fun `linux left dock places popover near left edge when left inset dominates`() {
        val screen = ScreenGeometry(
            bounds = Rectangle(0, 0, 1920, 1080),
            insets = Insets(0, 60, 0, 0),
        )
        val pos = ScreenPositionCalculator.calculate(screen, popoverSize, anchor = null, margin = 8, osName = "Linux")
        assertEquals(Point(60 + 8, 1080 - 380 - 8), pos)
    }

    @Test
    fun `click anchor centers horizontally and clamps within negative multi-monitor bounds`() {
        val screen = ScreenGeometry(
            bounds = Rectangle(-1920, 0, 1920, 1080),
            insets = Insets(0, 0, 40, 0),
        )
        // Клик у самого правого края левого монитора (x = -10, y = 1060)
        val pos = ScreenPositionCalculator.calculate(
            screen = screen,
            popoverSize = popoverSize,
            anchor = TrayClickAnchor(-10, 1060),
            margin = 8,
            osName = "Windows 11",
        )
        assertEquals(Point(-320 - 8, 1080 - 40 - 380 - 8), pos)
    }

    @Test
    fun `tiny screen smaller than popover does not throw on coerceIn`() {
        val screen = ScreenGeometry(
            bounds = Rectangle(0, 0, 200, 200),
            insets = Insets(0, 0, 0, 0),
        )
        val pos = ScreenPositionCalculator.calculate(screen, popoverSize, anchor = null, margin = 8, osName = "Linux")
        assertEquals(Point(8, 8), pos)
    }

    @Test
    fun `popover height follows content when it fits the work area`() {
        val screen = ScreenGeometry(Rectangle(0, 0, 1920, 1080), Insets(0, 0, 48, 0))
        assertEquals(270, ScreenPositionCalculator.fitHeight(contentHeight = 270, screen = screen, margin = 8))
    }

    @Test
    fun `popover height is capped by the work area minus margins`() {
        val screen = ScreenGeometry(Rectangle(0, 0, 1366, 600), Insets(0, 0, 40, 0))
        assertEquals(600 - 40 - 2 * 8, ScreenPositionCalculator.fitHeight(contentHeight = 900, screen = screen, margin = 8))
    }

    @Test
    fun `popover height never collapses below one pixel on a tiny screen`() {
        val screen = ScreenGeometry(Rectangle(0, 0, 100, 10), Insets(0, 0, 0, 0))
        assertEquals(1, ScreenPositionCalculator.fitHeight(contentHeight = 270, screen = screen, margin = 8))
    }
}
