package dev.mimir.ui.model

import dev.mimir.client.UpdateErrorCode
import dev.mimir.client.UpdateStatus
import dev.mimir.client.UpdateStatusDto

/** Ключ состояния, которое пользователь может скрыть кнопкой «Скрыть»: статус + версия; новая версия показывается снова. */
internal fun UpdateStatusDto.dismissKey(): String = "$status:${latestVersion.orEmpty()}"

/**
 * Причины, при которых повтор того же шага бессмыслен: сначала нужна свежая проверка.
 * `NO_CHECKSUM`: сбой загрузки чек-сумм не кэшируется, новая проверка запросит их снова.
 */
private val RECHECK_CODES = setOf(
    UpdateErrorCode.NO_CHECKSUM,
    UpdateErrorCode.NO_INSTALLABLE_FILE,
    UpdateErrorCode.NOTHING_TO_DOWNLOAD,
    UpdateErrorCode.NOT_DOWNLOADED,
)

internal fun retryFor(step: UpdateRetry, code: UpdateErrorCode): UpdateRetry = if (code in RECHECK_CODES) UpdateRetry.Check else step

internal fun UpdateStatusDto.toUi(dismissedKey: String? = null): UpdateUiState {
    val version = latestVersion.orEmpty()
    val state = when (status) {
        UpdateStatus.DISABLED -> UpdateUiState.Disabled
        // «Последняя версия» — только после успешной проверки; до неё (и после «Скрыть») актуальность неизвестна.
        UpdateStatus.IDLE -> if (lastCheckedAt != null) UpdateUiState.UpToDate(currentVersion) else UpdateUiState.Unknown(currentVersion)
        UpdateStatus.CHECKING -> UpdateUiState.Checking
        UpdateStatus.AVAILABLE -> UpdateUiState.Available(version, UpdateTexts.notes(releaseNotes), installKind(), htmlUrl)
        UpdateStatus.DOWNLOADING -> UpdateUiState.Downloading(version, downloadProgress.coerceIn(0f, 1f))
        UpdateStatus.READY_TO_INSTALL -> UpdateUiState.ReadyToInstall(version)
        UpdateStatus.INSTALLING -> UpdateUiState.Installing(version)
        UpdateStatus.ERROR -> {
            val step = when (failedStep) {
                "DOWNLOAD" -> UpdateRetry.Download
                "INSTALL" -> UpdateRetry.Install
                else -> UpdateRetry.Check
            }
            val code = UpdateErrorCode.parse(errorCode) ?: UpdateErrorCode.FAILED
            UpdateUiState.Error(UpdateTexts.errorText(step, code), retryFor(step, code), errorMessage?.takeIf { it.isNotBlank() })
        }
    }
    val dismissible = status == UpdateStatus.AVAILABLE || status == UpdateStatus.ERROR
    return if (dismissible && dismissedKey == dismissKey()) UpdateUiState.Unknown(currentVersion) else state
}

private fun UpdateStatusDto.installKind(): UpdateInstallKind = when (capability) {
    "DOWNLOAD_AND_LAUNCH" -> UpdateInstallKind.DownloadAndLaunch
    "IN_PLACE_REPLACE" -> UpdateInstallKind.InPlace
    else -> UpdateInstallKind.NotifyOnly
}
