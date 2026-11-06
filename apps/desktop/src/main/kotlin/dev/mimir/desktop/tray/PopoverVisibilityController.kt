package dev.mimir.desktop.tray

/** Экранные координаты клика по иконке трея; `null` вместо него — координаты неизвестны (Wayland-панели). */
data class TrayClickAnchor(val screenX: Int, val screenY: Int)

/**
 * Состояние видимости поповера. Клик по иконке при открытом поповере сначала отбирает у него фокус
 * ([onFocusLost] закрывает окно), и лишь через 10–50 мс приходит [onTrayActivate]: без подавления
 * в окне [debounceMs] поповер тут же открылся бы снова.
 */
class PopoverVisibilityController(
    private val debounceMs: Long = 200L,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    var visible: Boolean = false
        private set

    var lastAnchor: TrayClickAnchor? = null
        private set

    private var focusLostAt: Long? = null

    /** Возвращает новую видимость. */
    fun onTrayActivate(anchor: TrayClickAnchor?): Boolean {
        val lostAt = focusLostAt
        if (lostAt != null) {
            focusLostAt = null
            val elapsed = clockMillis() - lostAt
            // elapsed < 0 — часы ушли назад: считаем клик частью того же жеста.
            if (elapsed < debounceMs) return visible
        }
        visible = !visible
        if (visible) lastAnchor = anchor
        return visible
    }

    fun onFocusLost(): Boolean {
        if (visible) {
            visible = false
            focusLostAt = clockMillis()
        }
        return visible
    }

    fun dismiss() {
        visible = false
        focusLostAt = null
    }
}
