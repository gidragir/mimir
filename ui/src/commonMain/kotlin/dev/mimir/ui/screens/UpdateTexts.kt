package dev.mimir.ui.screens

import dev.mimir.ui.model.UpdateInstallKind

/** Тексты блока обновления: без технических терминов (контрольные суммы, установщики и т. п.). */
object UpdateTexts {
    const val TITLE = "О программе"
    const val CHECK = "Проверить обновления"
    const val CHECKING = "Ищу обновления…"
    const val RETRY = "Повторить"
    const val DISMISS = "Скрыть"
    const val INSTALL_NOW = "Установить"
    const val INSTALLING = "Устанавливаю обновление…"
    const val INSTALLING_HINT = "Mimir может закрыться и открыться снова."
    const val WHATS_NEW = "Что нового"
    const val SYNC_RUNNING_HINT = "Установка станет доступна после завершения синхронизации"
    const val DISABLED_NOTE = "Автоматическая проверка обновлений выключена."
    const val NOTES_TRUNCATED = "…"

    /** Сколько символов списка изменений показывать; длинные заметки обрезаются, полный текст — на странице релиза. */
    const val NOTES_LIMIT = 1200

    fun version(version: String) = "Версия v${version.removePrefix("v")}"

    fun upToDate(version: String) = "Установлена последняя версия · v${version.removePrefix("v")}"

    fun available(version: String) = "Доступно обновление v${version.removePrefix("v")}"

    fun downloading(percent: Int) = "Скачиваю обновление… $percent%"

    fun readyToInstall(version: String) = "Обновление v${version.removePrefix("v")} скачано и готово к установке"

    fun primaryAction(kind: UpdateInstallKind) = when (kind) {
        UpdateInstallKind.DownloadAndLaunch -> "Скачать и установить"
        UpdateInstallKind.InPlace -> "Обновить плагин"
        UpdateInstallKind.NotifyOnly -> "Открыть страницу релиза ↗"
    }

    /** Список изменений как простой текст, не длиннее [NOTES_LIMIT]; пустой — `null`. */
    fun notes(raw: String?): String? {
        val text = raw.orEmpty().lines().joinToString("\n") { plain(it) }.trim()
        if (text.isEmpty()) return null
        return if (text.length <= NOTES_LIMIT) text else text.take(NOTES_LIMIT).trimEnd() + NOTES_TRUNCATED
    }

    /** Убирает разметку Markdown: заголовки, жирный, код, маркеры списков -> «•». */
    private fun plain(line: String): String =
        line.trimEnd()
            .replace(Regex("^\\s*#{1,6}\\s*"), "")
            .replace(Regex("^(\\s*)[-*+]\\s+"), "$1• ")
            .replace("**", "").replace("__", "").replace("`", "")

    /** Что не получилось; вместе с [errorText] склеивается в одно сообщение. */
    enum class Failed(val summary: String) {
        Check("Не удалось проверить обновления."),
        Download("Не удалось скачать обновление."),
        Install("Не удалось установить обновление."),

        /** Демон не говорит, на каком шаге сбой. */
        Update("Не удалось обновить Mimir."),
    }

    /**
     * Сообщение для человека. Сырой текст демона (английский, технический) в интерфейс не попадает:
     * по нему выбирается только подсказка, что делать.
     */
    fun errorText(failed: Failed, raw: String?): String {
        val lower = raw.orEmpty().lowercase()
        val hint = when {
            "sync" in lower -> "Идёт синхронизация — попробуйте, когда она закончится."
            "rate limit" in lower || "too many" in lower -> "Слишком много запросов, попробуйте через несколько минут."
            "checksum" in lower || "sha-256" in lower || "mismatch" in lower || "corrupt" in lower ->
                "Файл повредился при загрузке — скачайте его заново."
            else -> "Проверьте подключение к интернету и повторите."
        }
        return "${failed.summary} $hint"
    }
}
