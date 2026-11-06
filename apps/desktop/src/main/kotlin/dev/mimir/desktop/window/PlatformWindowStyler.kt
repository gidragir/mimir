package dev.mimir.desktop.window

import androidx.compose.ui.graphics.Color
import dev.mimir.ui.MimirTokens
import java.awt.Frame
import java.awt.Window
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets
import java.util.logging.Logger
import javax.swing.JRootPane
import javax.swing.RootPaneContainer

/**
 * Красит нативную рамку окна в цвета темы, оставляя `undecorated = false` (тайлинг и снап ОС работают как обычно).
 * Windows — атрибуты DWM через FFM; macOS — свойства корневой панели; Linux — рамку рисует WM, no-op.
 */
object PlatformWindowStyler {
    private val logger: Logger = Logger.getLogger(PlatformWindowStyler::class.java.name)

    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_BORDER_COLOR = 34
    private const val DWMWA_CAPTION_COLOR = 35
    private const val DWMWA_TEXT_COLOR = 36

    fun apply(window: Window, isDark: Boolean, osName: String = System.getProperty("os.name").orEmpty()) {
        when {
            osName.startsWith("Windows") -> runCatching { applyWindowsDwmAttributes(window, isDark) }
                .onFailure { logger.fine("DWM styling skipped: $it") }
            osName.startsWith("Mac") || osName.contains("OS X") ->
                (window as? RootPaneContainer)?.let { applyMacRootPaneProperties(it.rootPane, isDark) }
            else -> Unit
        }
    }

    /** Compose ARGB → Win32 COLORREF (`0x00BBGGRR`). */
    internal fun toWin32ColorRef(color: Color): Int {
        val r = (color.red * 255f + 0.5f).toInt().coerceIn(0, 255)
        val g = (color.green * 255f + 0.5f).toInt().coerceIn(0, 255)
        val b = (color.blue * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (b shl 16) or (g shl 8) or r
    }

    internal fun applyMacRootPaneProperties(rootPane: JRootPane, isDark: Boolean) {
        rootPane.putClientProperty(
            "apple.awt.application.appearance",
            if (isDark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua",
        )
        rootPane.putClientProperty("apple.awt.fullWindowContent", true)
        rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
        rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
    }

    private fun applyWindowsDwmAttributes(window: Window, isDark: Boolean) {
        val linker = Linker.nativeLinker()
        Arena.ofConfined().use { arena ->
            val user32 = SymbolLookup.libraryLookup("user32.dll", arena)
            val dwm = SymbolLookup.libraryLookup("dwmapi.dll", arena)
            val hwnd = findOwnHwnd(linker, user32, arena, (window as? Frame)?.title) ?: return
            val setAttr = linker.downcallHandle(
                dwm.find("DwmSetWindowAttribute").orElseThrow(),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                ),
            )
            fun set(attr: Int, value: Int) {
                val seg = arena.allocate(ValueLayout.JAVA_INT).also { it.set(ValueLayout.JAVA_INT, 0, value) }
                setAttr.invoke(hwnd, attr, seg, 4)
            }
            val surface = if (isDark) MimirTokens.DarkSurface else MimirTokens.LightSurface
            val text = if (isDark) MimirTokens.DarkText else MimirTokens.LightText
            set(DWMWA_USE_IMMERSIVE_DARK_MODE, if (isDark) 1 else 0)
            set(DWMWA_CAPTION_COLOR, toWin32ColorRef(surface))
            set(DWMWA_BORDER_COLOR, toWin32ColorRef(surface))
            set(DWMWA_TEXT_COLOR, toWin32ColorRef(text))
        }
    }

    /** HWND окна `SunAwtFrame` текущего процесса; при нескольких — с совпадающим заголовком, иначе первое. */
    private fun findOwnHwnd(linker: Linker, user32: SymbolLookup, arena: Arena, title: String?): MemorySegment? {
        val findEx = linker.downcallHandle(
            user32.find("FindWindowExW").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ),
        )
        val getPid = linker.downcallHandle(
            user32.find("GetWindowThreadProcessId").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        )
        val getText = linker.downcallHandle(
            user32.find("GetWindowTextW").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
        )
        val className = arena.allocateFrom("SunAwtFrame", StandardCharsets.UTF_16LE)
        val pidOut = arena.allocate(ValueLayout.JAVA_INT)
        val buf = arena.allocate(512L)
        val ownPid = ProcessHandle.current().pid().toInt()
        var after = MemorySegment.NULL
        var first: MemorySegment? = null
        while (true) {
            val h = findEx.invoke(MemorySegment.NULL, after, className, MemorySegment.NULL) as MemorySegment
            if (h == MemorySegment.NULL) break
            after = h
            getPid.invoke(h, pidOut)
            if (pidOut.get(ValueLayout.JAVA_INT, 0) != ownPid) continue
            if (first == null) first = h
            val len = getText.invoke(h, buf, 256) as Int
            val caption = if (len > 0) String(buf.toArray(ValueLayout.JAVA_BYTE), 0, len * 2, StandardCharsets.UTF_16LE) else ""
            if (title != null && caption == title) return h
        }
        return first
    }
}
