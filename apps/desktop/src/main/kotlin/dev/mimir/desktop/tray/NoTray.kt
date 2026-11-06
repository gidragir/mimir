package dev.mimir.desktop.tray

import java.util.logging.Logger

class NoTray : Tray {
    override val available = false

    override fun show(
        status: String,
        onOpen: () -> Unit,
        onSyncNow: () -> Unit,
        onQuit: () -> Unit,
        onActivate: (TrayClickAnchor?) -> Unit,
    ) {
        logger.info("Tray is not available on this system; the window close button quits the application")
    }

    override fun update(status: String) = Unit

    override fun close() = Unit

    private companion object {
        val logger: Logger = Logger.getLogger(NoTray::class.java.name)
    }
}
