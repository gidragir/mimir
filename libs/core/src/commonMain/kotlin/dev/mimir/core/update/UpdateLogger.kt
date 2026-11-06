package dev.mimir.core.update

/**
 * Порт журнала обновлений: `libs/core` не знает о платформенном логгере, его подставляет точка сборки
 * (runtime — `java.util.logging`). Пишутся только сбои: причина нужна поддержке, в UI уходит код ошибки.
 */
fun interface UpdateLogger {
    fun error(message: String, cause: Throwable?)

    companion object {
        val None: UpdateLogger = UpdateLogger { _, _ -> }
    }
}

/** Текст ошибки для журнала и `UpdateState.Error`: без сообщения (сокеты, таймауты) остаётся хотя бы тип. */
internal fun Throwable.describe(fallback: String): String {
    val type = this::class.simpleName
    val text = message?.takeIf { it.isNotBlank() }
    return when {
        text != null -> text
        type != null -> "$fallback: $type"
        else -> fallback
    }
}
