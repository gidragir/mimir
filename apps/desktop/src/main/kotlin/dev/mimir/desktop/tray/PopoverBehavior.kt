package dev.mimir.desktop.tray

import java.awt.Window

/**
 * Как показывать поповер трея на этой платформе: тип окна и закрывается ли он при потере фокуса.
 *
 * niri запускает X11-клиентов через xwayland-satellite: окно `POPUP` (override-redirect) он показывает только
 * как xdg_popup сфокусированного X11-окна приложения — без фокуса на главном окне поповер невидим, с фокусом
 * обрезан его областью. Обычное окно фиксированного размера niri делает плавающим, но фокус у него нестабилен:
 * через ~200 мс после показа он уходит, а с focus-follows-mouse меняется, пока курсор идёт к окну. Поэтому на
 * niri поповер закрывают Esc, повторный клик по иконке и кнопки, а не потеря фокуса.
 */
data class PopoverBehavior(val windowType: Window.Type, val closesOnFocusLoss: Boolean) {
    companion object {
        fun detect(
            osName: String = System.getProperty("os.name").orEmpty(),
            env: Map<String, String> = System.getenv(),
        ): PopoverBehavior {
            val linux = osName.contains("linux", ignoreCase = true)
            val niri = env["NIRI_SOCKET"].orEmpty().isNotEmpty() ||
                env["XDG_CURRENT_DESKTOP"].orEmpty().split(':').any { it.equals("niri", ignoreCase = true) }
            return if (linux && niri) PopoverBehavior(Window.Type.NORMAL, closesOnFocusLoss = false)
            else PopoverBehavior(Window.Type.POPUP, closesOnFocusLoss = true)
        }
    }
}
