package dev.mimir.desktop.tray

import dev.mimir.core.BuildConfig
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon

class AwtTray : Tray {
    private var icon: TrayIcon? = null

    override val available get() = icon != null

    override fun show(status: String, onOpen: () -> Unit, onQuit: () -> Unit) {
        val image = Toolkit.getDefaultToolkit().getImage(AwtTray::class.java.getResource("/icon.png"))
        val menu = PopupMenu().apply {
            add(MenuItem("Open").apply { addActionListener { onOpen() } })
            add(MenuItem("Quit").apply { addActionListener { onQuit() } })
        }
        icon = TrayIcon(image, status, menu).apply {
            isImageAutoSize = true
            addActionListener { onOpen() }
        }
        SystemTray.getSystemTray().add(icon)
    }

    override fun update(status: String) {
        icon?.toolTip = status
    }

    override fun close() {
        icon?.let { SystemTray.getSystemTray().remove(it) }
        icon = null
    }
}
