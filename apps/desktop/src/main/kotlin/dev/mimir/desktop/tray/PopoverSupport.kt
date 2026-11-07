package dev.mimir.desktop.tray

/**
 * Можно ли показать поповер трея как самостоятельное окно у иконки.
 *
 * niri запускает X11-клиентов через xwayland-satellite: окно `Window.Type.POPUP` (override-redirect) он
 * показывает только как xdg_popup сфокусированного X11-окна приложения — без фокуса на главном окне поповер
 * невидим, с фокусом обрезан его областью и через ~200 мс теряет фокус (закрывается). Там одиночный клик
 * открывает главное окно, а меню остаётся на правом клике (DbusMenu).
 */
object PopoverSupport {
    fun isSupported(
        osName: String = System.getProperty("os.name").orEmpty(),
        env: Map<String, String> = System.getenv(),
    ): Boolean {
        if (!osName.contains("linux", ignoreCase = true)) return true
        val niri = env["NIRI_SOCKET"].orEmpty().isNotEmpty() ||
            env["XDG_CURRENT_DESKTOP"].orEmpty().split(':').any { it.equals("niri", ignoreCase = true) }
        return !niri
    }
}
