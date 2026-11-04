package dev.mimir.desktop

import java.awt.Desktop
import java.awt.Frame
import java.awt.Window
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout

object PlatformFocus {
    fun activate(window: Window) {
        window.isVisible = true
        if (window is Frame) {
            window.extendedState = Frame.NORMAL
        }
        window.toFront()
        window.requestFocus()

        if (isWindows()) {
            try {
                window.isAlwaysOnTop = true
                window.isAlwaysOnTop = false
            } catch (_: Throwable) {
            }
        }

        try {
            if (isMac()) {
                if (Desktop.isDesktopSupported()) {
                    val desktop = Desktop.getDesktop()
                    if (desktop.isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
                        desktop.requestForeground(true)
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun allowForegroundTransfer() {
        if (!isWindows()) return
        try {
            val linker = Linker.nativeLinker()
            val user32 = SymbolLookup.libraryLookup("user32.dll", Arena.global())
            val allowSetForegroundWindow = user32.find("AllowSetForegroundWindow").orElse(null) ?: return
            val methodHandle = linker.downcallHandle(
                allowSetForegroundWindow,
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                ),
            )
            // ASFW_ANY = -1
            methodHandle.invoke(-1)
        } catch (_: Throwable) {
        }
    }

    private fun isMac(): Boolean = System.getProperty("os.name", "").lowercase().contains("mac")
    private fun isWindows(): Boolean = System.getProperty("os.name", "").lowercase().contains("win")
}
