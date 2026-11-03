package dev.mimir.desktop.tray

import dev.mimir.core.BuildConfig
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon

class AwtTray : Tray {
    private var icon: TrayIcon? = null
    private var menu: PopupMenu? = null
    private var updateItem: MenuItem? = null

    override val available get() = icon != null

    override fun show(status: String, onOpen: () -> Unit, onSyncNow: () -> Unit, onQuit: () -> Unit) {
        val image = Toolkit.getDefaultToolkit().getImage(AwtTray::class.java.getResource("/icon.png"))
        val popup = PopupMenu().apply {
            add(MenuItem("Открыть").apply { addActionListener { onOpen() } })
            add(MenuItem("Синхронизировать сейчас").apply { addActionListener { onSyncNow() } })
            add(MenuItem("Выход").apply { addActionListener { onQuit() } })
        }
        menu = popup
        icon = TrayIcon(image, status, popup).apply {
            isImageAutoSize = true
            addActionListener { onOpen() }
        }
        SystemTray.getSystemTray().add(icon)
    }

    override fun update(status: String) {
        icon?.toolTip = status
    }

    override fun setUpdate(label: String?, onClick: () -> Unit) {
        val popup = menu ?: return
        updateItem?.let { popup.remove(it) }
        updateItem = label?.let { text ->
            MenuItem(text).apply { addActionListener { onClick() } }.also { popup.insert(it, 0) }
        }
    }

    override fun close() {
        updateItem = null
        menu = null
        icon?.let { SystemTray.getSystemTray().remove(it) }
        icon = null
    }
}
