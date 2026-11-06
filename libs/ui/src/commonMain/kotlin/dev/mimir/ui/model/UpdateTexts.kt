package dev.mimir.ui.model

import dev.mimir.client.UpdateErrorCode

/** Что повторяет кнопка «Повторить» после ошибки: именно тот шаг, который не удался. */
enum class UpdateRetry { Check, Download, Install }

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
    const val NOT_CHECKED = "Обновления ещё не проверялись."
    const val NOTES_TRUNCATED = "…"
    const val DETAILS_HINT = "Технические сведения об ошибке — пригодятся, если будете писать в поддержку."

    /** Переключатель сворачиваемых подробностей ошибки. */
    fun details(expanded: Boolean) = "Подробности " + if (expanded) "▾" else "▸"

    /** Сколько символов списка изменений показывать; длинные заметки обрезаются, полный текст — на странице релиза. */
    const val NOTES_LIMIT = 1200

    fun upToDate(version: String) = "Установлена последняя версия · v${version.removePrefix("v")}"

    fun available(version: String) = "Доступно обновление v${version.removePrefix("v")}"

    fun downloading(version: String, percent: Int) = "Скачиваю обновление v${version.removePrefix("v")}… $percent%"

    fun readyToInstall(version: String) = "Обновление v${version.removePrefix("v")} скачано и готово к установке"

    /**
     * Главная кнопка по способу обновления. Установка отдельным шагом, поэтому «Скачать»: после скачивания
     * появляется «Установить».
     */
    fun primaryAction(kind: UpdateInstallKind) = when (kind) {
        UpdateInstallKind.DownloadAndLaunch -> "Скачать"
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

    private fun summary(step: UpdateRetry) = when (step) {
        UpdateRetry.Check -> "Не удалось проверить обновления."
        UpdateRetry.Download -> "Не удалось скачать обновление."
        UpdateRetry.Install -> "Не удалось установить обновление."
    }

    /**
     * Сообщение для человека по устойчивому [code]; сырой текст демона в интерфейс не попадает.
     * Сбои «по существу» (сеть, лимит, файл) идут с кратким итогом шага, отказы по состоянию — одной понятной фразой.
     */
    fun errorText(step: UpdateRetry, code: UpdateErrorCode): String = when (code) {
        UpdateErrorCode.NETWORK -> "${summary(step)} Проверьте подключение к интернету и повторите."
        UpdateErrorCode.RATE_LIMITED -> "${summary(step)} Слишком много запросов, попробуйте через несколько минут."
        UpdateErrorCode.CHECKSUM_MISMATCH -> "${summary(step)} Файл повредился при загрузке — скачайте его заново."
        UpdateErrorCode.HTTP_ERROR -> "${summary(step)} Файл обновления сейчас недоступен на сервере — попробуйте позже."
        UpdateErrorCode.NO_CHECKSUM ->
            "${summary(step)} Не получилось проверить подлинность файла — проверьте обновления ещё раз чуть позже."
        UpdateErrorCode.DISK -> "${summary(step)} Файл не удалось сохранить на диск — проверьте свободное место и повторите."
        UpdateErrorCode.FAILED -> "${summary(step)} Попробуйте ещё раз позже."
        UpdateErrorCode.UPDATES_DISABLED -> "Проверка обновлений выключена."
        UpdateErrorCode.NO_INSTALLABLE_FILE ->
            "Для этой системы нет готового установщика. Откройте страницу релиза и обновитесь вручную."
        UpdateErrorCode.NOTHING_TO_DOWNLOAD -> "Сейчас нечего скачивать. Проверьте обновления ещё раз."
        UpdateErrorCode.DOWNLOAD_IN_PROGRESS -> "Обновление уже скачивается."
        UpdateErrorCode.INSTALL_IN_PROGRESS -> "Обновление уже устанавливается."
        UpdateErrorCode.NOT_DOWNLOADED -> "Сначала нужно скачать обновление."
        UpdateErrorCode.SYNC_RUNNING -> "Идёт синхронизация — установите обновление, когда она закончится."
    }
}
