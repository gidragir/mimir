package dev.mimir.core.update

/** Фейк [UpdateLogger]: запоминает записи журнала. */
class RecordingUpdateLogger : UpdateLogger {
    data class Entry(val message: String, val cause: Throwable?)

    val entries = mutableListOf<Entry>()

    override fun error(message: String, cause: Throwable?) {
        entries += Entry(message, cause)
    }
}
