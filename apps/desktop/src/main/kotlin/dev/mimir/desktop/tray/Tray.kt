package dev.mimir.desktop.tray

interface Tray {
    /** true, если трей реально показан: тогда закрытие окна сворачивает в трей, а не завершает приложение. */
    val available: Boolean

    fun show(status: String, onOpen: () -> Unit, onSyncNow: () -> Unit, onQuit: () -> Unit)

    fun update(status: String)

    /**
     * Пункт «Доступно обновление vX.Y.Z» в самом верху меню; `null` убирает его. Нажатие — [onClick].
     * Вызывается после [show]; трей без меню (или без трея) может его игнорировать.
     */
    fun setUpdate(label: String?, onClick: () -> Unit) = Unit

    fun close()
}

/**
 * Windows — AWT [java.awt.SystemTray]; Linux — StatusNotifierItem по D-Bus (работает и в Wayland), которому при отсутствии
 * StatusNotifierWatcher остаётся `available == false`; иначе [NoTray].
 */
fun createTray(
    osName: String = System.getProperty("os.name"),
    awtTraySupported: () -> Boolean = { java.awt.SystemTray.isSupported() },
    dbusTray: () -> Tray = { DbusStatusNotifierTray() },
): Tray = when {
    osName.startsWith("Windows") && awtTraySupported() -> AwtTray()
    osName.startsWith("Linux") -> dbusTray()
    else -> NoTray()
}
