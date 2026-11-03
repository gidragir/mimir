package dev.mimir.client

import dev.mimir.core.update.UpdateState
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
    is UpdateState.Error -> previousState.toDto(currentVersion).copy(status = UpdateStatus.ERROR, errorMessage = message)
}
