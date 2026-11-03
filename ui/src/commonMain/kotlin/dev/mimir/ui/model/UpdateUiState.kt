package dev.mimir.ui.model

/** Как именно обновляется эта сборка: определяет подпись и действие главной кнопки. */
enum class UpdateInstallKind {
    /** Скачать установщик и запустить его (Windows, Android). */
    DownloadAndLaunch,

    /** Заменить файлы на месте (плагин Obsidian). */
    InPlace,

    /** Приложение само не обновляется: открыть страницу релиза (Linux, Docker, расширение). */
    NotifyOnly,
}

/** Состояние обновления для интерфейса; не зависит от типов `core` и от формата API демона. */
sealed interface UpdateUiState {
    /** Ещё нет данных (или демон недоступен): карточка не показывается. */
    data object Hidden : UpdateUiState

    /** Проверка обновлений выключена (`MIMIR_UPDATE_CHECK=false`) — это выбор, а не ошибка. */
    data object Disabled : UpdateUiState

    data class UpToDate(val version: String) : UpdateUiState

    data object Checking : UpdateUiState

    data class Available(
        val version: String,
        val notes: String?,
        val kind: UpdateInstallKind,
        val releaseUrl: String?,
    ) : UpdateUiState {
        val canInstallInApp: Boolean get() = kind != UpdateInstallKind.NotifyOnly
    }

    data class Downloading(val version: String, val progress: Float) : UpdateUiState

    data class ReadyToInstall(val version: String) : UpdateUiState

    data class Installing(val version: String) : UpdateUiState

    /** [message] уже написан для человека. */
    data class Error(val message: String) : UpdateUiState

    /** Есть что предложить пользователю: пункт трея и бейдж. */
    val isActionable: Boolean get() = this is Available || this is ReadyToInstall
}
