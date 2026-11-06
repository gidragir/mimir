package dev.mimir.desktop.tray

import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * Windows и macOS. Без [java.awt.PopupMenu]: одиночный клик открывает Compose-поповер ([TrayPopoverWindow]),
 * двойной — главное окно. Пункт «Доступно обновление» живёт в самом поповере, поэтому [setUpdate] не переопределён.
 */
class AwtTray : Tray {
    private var icon: TrayIcon? = null

    override val available get() = icon != null

    override fun show(
        status: String,
        onOpen: () -> Unit,
        onSyncNow: () -> Unit,
        onQuit: () -> Unit,
        onActivate: (TrayClickAnchor?) -> Unit,
    ) {
        val image = Toolkit.getDefaultToolkit().getImage(AwtTray::class.java.getResource("/tray.png"))
        icon = TrayIcon(image, status).apply {
            isImageAutoSize = true
            addMouseListener(object : MouseAdapter() {
                override fun mouseReleased(e: MouseEvent) {
                    if (e.clickCount >= 2 && SwingUtilities.isLeftMouseButton(e)) {
                        onOpen()
                    } else {
                        onActivate(TrayClickAnchor(e.xOnScreen, e.yOnScreen))
                    }
                }
            })
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
