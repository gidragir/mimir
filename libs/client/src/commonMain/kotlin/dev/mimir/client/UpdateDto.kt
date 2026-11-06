package dev.mimir.client

import dev.mimir.core.update.UpdateApplyResult
import dev.mimir.core.update.UpdateState
import dev.mimir.core.update.UpdateStep
import kotlinx.serialization.Serializable

/** Статус обновления для клиентов API (спека §6.1). Имена записываются в JSON как есть. */
enum class UpdateStatus {
    IDLE,
    CHECKING,
    AVAILABLE,
    DOWNLOADING,
    READY_TO_INSTALL,
    INSTALLING,
    ERROR,
    DISABLED,
}

/**
 * Устойчивая причина отказа/ошибки: клиент выбирает по ней текст для человека, а не разбирает английское сообщение.
 * Имена попадают в JSON (`errorCode` в статусе и `code` в теле ошибки) как есть.
 */
enum class UpdateErrorCode(val defaultMessage: String) {
    UPDATES_DISABLED("Update checks are disabled"),
    NO_INSTALLABLE_FILE("This update has no installable file for this platform"),
    NOTHING_TO_DOWNLOAD("No update is available to download"),
    DOWNLOAD_IN_PROGRESS("A download is already in progress"),
    INSTALL_IN_PROGRESS("An install is already in progress"),
    NOT_DOWNLOADED("No downloaded update to install"),
    SYNC_RUNNING("A sync run is in progress; try again when it finishes"),
    RATE_LIMITED("Too many requests; try again later"),
    CHECKSUM_MISMATCH("The downloaded file did not pass verification"),

    /** Сервер ответил 4xx/5xx на скачивание файла обновления (`HTTP NNN for <url>`). */
    HTTP_ERROR("The update file could not be downloaded from the server"),

    /** У релиза нет контрольной суммы файла (или её не удалось получить): без неё файл не ставится. */
    NO_CHECKSUM("The update file has no published checksum"),

    /** Не удалось создать или записать временный файл обновления. */
    DISK("The update file could not be saved to disk"),

    /** Нет связи с демоном или GitHub (на стороне клиента). */
    NETWORK("Network error"),
    FAILED("Update failed"),
    ;

    companion object {
        fun parse(name: String?): UpdateErrorCode? = entries.firstOrNull { it.name == name }
    }
}

/** Ошибка команды обновления с устойчивым [code]. */
class UpdateCommandException(val code: UpdateErrorCode, message: String = code.defaultMessage) : IllegalStateException(message)

/** `HTTP 404 for https://…` — отказ сервера при скачивании ассета (формат `KtorUpdateHttpClient`/`FetchUpdateHttpClient`). */
private val HTTP_STATUS_FOR_URL = Regex("""\bhttp [45]\d\d for \S""")

/**
 * Единственное место, где по тексту ошибки менеджера определяется причина (менеджер отдаёт только строку).
 * [retryAfterSeconds] != null — GitHub попросил подождать. Порядок важен: «no checksum published» содержит
 * «checksum», но это не повреждённый файл. Новый код старому клиенту неизвестен — он покажет `FAILED`.
 */
fun classifyUpdateError(message: String?, retryAfterSeconds: Long? = null): UpdateErrorCode {
    val lower = message.orEmpty().lowercase()
    return when {
        retryAfterSeconds != null || "rate limit" in lower -> UpdateErrorCode.RATE_LIMITED
        "no checksum published" in lower -> UpdateErrorCode.NO_CHECKSUM
        "checksum" in lower || "sha-256" in lower || "sha256" in lower || "mismatch" in lower || "verification" in lower ->
            UpdateErrorCode.CHECKSUM_MISMATCH
        HTTP_STATUS_FOR_URL.containsMatchIn(lower) -> UpdateErrorCode.HTTP_ERROR
        "cannot write update file" in lower -> UpdateErrorCode.DISK
        else -> UpdateErrorCode.FAILED
    }
}

/**
 * Снимок [UpdateState] для `GET /api/v1/update`. Локальные пути скачанных файлов в DTO не попадают.
 * `ERROR` повторяет сведения о релизе из состояния, в котором произошла ошибка.
 */
@Serializable
data class UpdateStatusDto(
    val currentVersion: String,
    val status: UpdateStatus,
    val latestVersion: String? = null,
    val releaseNotes: String? = null,
    val publishedAt: String? = null,
    val capability: String? = null,
    /** Доля скачанного, 0..1. */
    val downloadProgress: Float = 0f,
    val htmlUrl: String? = null,
    val errorMessage: String? = null,
    /** Для `ERROR`: [UpdateErrorCode] по имени. */
    val errorCode: String? = null,
    /** Для `ERROR`: какой шаг не удался (`CHECK`, `DOWNLOAD`, `INSTALL`) — его и повторяет «Повторить». */
    val failedStep: String? = null,
    val lastCheckedAt: String? = null,
)

/** Ответ `POST /api/v1/update/apply`. */
@Serializable
data class UpdateApplyDto(
    /** `installer_launched`, `applied` или `notification`. */
    val result: String,
    val message: String,
    val requiresRestart: Boolean = false,
    val url: String? = null,
)

fun disabledUpdateStatus(currentVersion: String) = UpdateStatusDto(currentVersion, UpdateStatus.DISABLED)

fun UpdateState.toDto(currentVersion: String): UpdateStatusDto = when (this) {
    is UpdateState.Idle -> UpdateStatusDto(
        currentVersion,
        UpdateStatus.IDLE,
        latestVersion = latestKnownVersion?.toString(),
        lastCheckedAt = lastCheckedAt?.toString(),
    )
    UpdateState.Checking -> UpdateStatusDto(currentVersion, UpdateStatus.CHECKING)
    is UpdateState.Available -> UpdateStatusDto(
        currentVersion,
        UpdateStatus.AVAILABLE,
        latestVersion = release.version.toString(),
        releaseNotes = release.releaseNotes,
        publishedAt = release.publishedAt.toString(),
        capability = capability.name,
        htmlUrl = release.htmlUrl,
    )
    is UpdateState.Downloading -> UpdateStatusDto(
        currentVersion,
        UpdateStatus.DOWNLOADING,
        latestVersion = release.version.toString(),
        releaseNotes = release.releaseNotes,
        publishedAt = release.publishedAt.toString(),
        downloadProgress = progress.coerceIn(0f, 1f),
        htmlUrl = release.htmlUrl,
    )
    is UpdateState.ReadyToInstall -> UpdateStatusDto(
        currentVersion,
        UpdateStatus.READY_TO_INSTALL,
        latestVersion = release.version.toString(),
        releaseNotes = release.releaseNotes,
        publishedAt = release.publishedAt.toString(),
        capability = capability.name,
        downloadProgress = 1f,
        htmlUrl = release.htmlUrl,
    )
    is UpdateState.Installing -> UpdateStatusDto(
        currentVersion,
        UpdateStatus.INSTALLING,
        latestVersion = release.version.toString(),
        releaseNotes = release.releaseNotes,
        publishedAt = release.publishedAt.toString(),
        htmlUrl = release.htmlUrl,
    )
    is UpdateState.Error -> previousState.toDto(currentVersion).copy(
        status = UpdateStatus.ERROR,
        errorMessage = message,
        errorCode = classifyUpdateError(message).name,
        failedStep = step.name,
    )
}

/** `null` для [UpdateApplyResult.Failed]: ошибка отдаётся отдельным ответом. */
fun UpdateApplyResult.toDto(): UpdateApplyDto? = when (this) {
    is UpdateApplyResult.Success -> UpdateApplyDto("applied", message, requiresRestart)
    is UpdateApplyResult.LaunchedInstaller -> UpdateApplyDto("installer_launched", message, requiresRestart = true)
    is UpdateApplyResult.NotificationDispatched -> UpdateApplyDto("notification", "Update details: $url", url = url)
    is UpdateApplyResult.Failed -> null
}

// Предпроверки команд: одни и те же правила для маршрутов демона и для встроенного фасада.

/** Ошибка последнего шага не мешает повторить: опираемся на состояние до неё. */
fun UpdateState.effectiveState(): UpdateState = if (this is UpdateState.Error) previousState else this

/** `null` — скачивать можно. */
fun UpdateState.downloadBlocker(): UpdateErrorCode? {
    val state = effectiveState()
    return when {
        state is UpdateState.Downloading -> UpdateErrorCode.DOWNLOAD_IN_PROGRESS
        state !is UpdateState.Available -> UpdateErrorCode.NOTHING_TO_DOWNLOAD
        state.asset == null -> UpdateErrorCode.NO_INSTALLABLE_FILE
        else -> null
    }
}

/** `null` — устанавливать можно. */
fun UpdateState.applyBlocker(syncing: Boolean): UpdateErrorCode? = when {
    effectiveState() is UpdateState.Installing -> UpdateErrorCode.INSTALL_IN_PROGRESS
    effectiveState() !is UpdateState.ReadyToInstall -> UpdateErrorCode.NOT_DOWNLOADED
    syncing -> UpdateErrorCode.SYNC_RUNNING
    else -> null
}
