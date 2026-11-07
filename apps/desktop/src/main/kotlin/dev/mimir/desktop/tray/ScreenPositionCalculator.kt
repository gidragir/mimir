package dev.mimir.desktop.tray

import java.awt.Dimension
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle

data class ScreenGeometry(val bounds: Rectangle, val insets: Insets)

/** Чистая геометрия: куда поставить поповер у иконки трея. Не зависит от AWT-окон и экрана. */
object ScreenPositionCalculator {
    fun calculate(
        screen: ScreenGeometry,
        popoverSize: Dimension,
        anchor: TrayClickAnchor? = null,
        margin: Int = 8,
        osName: String = System.getProperty("os.name").orEmpty(),
    ): Point {
        val b = screen.bounds
        val i = screen.insets
        val left = b.x + i.left
        val top = b.y + i.top
        val right = b.x + b.width - i.right
        val bottom = b.y + b.height - i.bottom
        // coerceAtLeast: экран меньше поповера не должен давать пустой диапазон в coerceIn.
        val minX = left + margin
        val maxX = (right - popoverSize.width - margin).coerceAtLeast(minX)
        val minY = top + margin
        val maxY = (bottom - popoverSize.height - margin).coerceAtLeast(minY)

        val rawX: Int
        val rawY: Int
        if (anchor != null) {
            rawX = anchor.screenX - popoverSize.width / 2
            rawY = if (anchor.screenY < b.y + b.height / 2) minY else maxY
        } else {
            val isMac = osName.contains("mac", ignoreCase = true) || osName.contains("os x", ignoreCase = true)
            val topBar = isMac || i.top > i.bottom
            val leftBar = !isMac && i.left > i.right && i.left > i.bottom && i.left > i.top
            rawX = if (leftBar) minX else maxX
            rawY = if (topBar) minY else maxY
        }
        return Point(rawX.coerceIn(minX, maxX), rawY.coerceIn(minY, maxY))
    }

    /** Высота поповера по содержимому, но не выше рабочей области экрана за вычетом отступов (лишнее прокручивается). */
    fun fitHeight(contentHeight: Int, screen: ScreenGeometry, margin: Int = 8): Int {
        val available = screen.bounds.height - screen.insets.top - screen.insets.bottom - 2 * margin
        return contentHeight.coerceAtMost(available).coerceAtLeast(1)
    }
}
