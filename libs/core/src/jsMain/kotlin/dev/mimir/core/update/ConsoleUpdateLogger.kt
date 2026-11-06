package dev.mimir.core.update

/** Журнал обновлений JS-клиентов (плагин Obsidian, расширение): консоль разработчика. */
val ConsoleUpdateLogger: UpdateLogger = UpdateLogger { message, cause ->
    if (cause != null) console.warn(message, cause) else console.warn(message)
}
