package dev.mimir.desktop.tray

interface Tray {
    /** true, если трей реально показан: тогда закрытие окна сворачивает в трей, а не завершает приложение. */
    val available: Boolean

    /**
     * [onOpen] — развернуть главное окно (двойной клик, «Открыть»); [onActivate] — одиночный клик по иконке:
     * открыть/закрыть поповер у иконки. [TrayClickAnchor] — экранные координаты клика или `null`, если они неизвестны.
     */
    fun show(
        status: String,
        onOpen: () -> Unit,
        onSyncNow: () -> Unit,
        onQuit: () -> Unit,
        onActivate: (TrayClickAnchor?) -> Unit = { onOpen() },
    )

    fun update(status: String)

    /**
     * Пункт «Доступно обновление vX.Y.Z» в самом верху меню; `null` убирает его. Нажатие — [onClick].
     * Вызывается после [show]; трей без меню (или без трея) может его игнорировать.
     */
    fun setUpdate(label: String?, onClick: () -> Unit) = Unit

    fun close()
}

/**
 * Windows и macOS — AWT [java.awt.SystemTray]; Linux — StatusNotifierItem по D-Bus (работает и в Wayland), которому при отсутствии
 * StatusNotifierWatcher остаётся `available == false`; иначе [NoTray].
 */
fun createTray(
    osName: String = System.getProperty("os.name"),
    awtTraySupported: () -> Boolean = { java.awt.SystemTray.isSupported() },
    dbusTray: () -> Tray = { DbusStatusNotifierTray() },
): Tray = when {
    (osName.startsWith("Windows") || osName.startsWith("Mac") || osName.contains("OS X")) && awtTraySupported() -> AwtTray()
    osName.startsWith("Linux") -> dbusTray()
    else -> NoTray()
}
