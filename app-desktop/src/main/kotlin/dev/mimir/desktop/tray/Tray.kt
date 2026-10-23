package dev.mimir.desktop.tray

interface Tray {
    /** true, если трей реально показан: тогда закрытие окна сворачивает в трей, а не завершает приложение. */
    val available: Boolean

    fun show(status: String, onOpen: () -> Unit, onQuit: () -> Unit)

    fun update(status: String)

    fun close()
}

fun createTray(
    osName: String = System.getProperty("os.name"),
    awtTraySupported: () -> Boolean = { java.awt.SystemTray.isSupported() },
): Tray =
    if (osName.startsWith("Windows") && awtTraySupported()) AwtTray() else NoTray()
