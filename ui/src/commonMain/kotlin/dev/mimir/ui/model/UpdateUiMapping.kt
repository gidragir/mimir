package dev.mimir.ui.model

import dev.mimir.client.UpdateStatus
import dev.mimir.client.UpdateStatusDto
import dev.mimir.ui.screens.UpdateTexts

/** Ключ состояния, которое пользователь может скрыть кнопкой «Скрыть»: статус + версия; новая версия показывается снова. */
internal fun UpdateStatusDto.dismissKey(): String = "$status:${latestVersion.orEmpty()}"

internal fun UpdateStatusDto.toUi(dismissedKey: String? = null): UpdateUiState {
    val version = latestVersion.orEmpty()
    val state = when (status) {
        UpdateStatus.DISABLED -> UpdateUiState.Disabled
        UpdateStatus.IDLE -> UpdateUiState.UpToDate(currentVersion)
        UpdateStatus.CHECKING -> UpdateUiState.Checking
        UpdateStatus.AVAILABLE -> UpdateUiState.Available(version, UpdateTexts.notes(releaseNotes), installKind(), htmlUrl)
        UpdateStatus.DOWNLOADING -> UpdateUiState.Downloading(version, downloadProgress.coerceIn(0f, 1f))
        UpdateStatus.READY_TO_INSTALL -> UpdateUiState.ReadyToInstall(version)
        UpdateStatus.INSTALLING -> UpdateUiState.Installing(version)
        UpdateStatus.ERROR -> UpdateUiState.Error(
            UpdateTexts.errorText(
                if (latestVersion == null) UpdateTexts.Failed.Check else UpdateTexts.Failed.Update,
                errorMessage,
            ),
        )
    }
    val dismissible = status == UpdateStatus.AVAILABLE || status == UpdateStatus.ERROR
    return if (dismissible && dismissedKey == dismissKey()) UpdateUiState.UpToDate(currentVersion) else state
}

private fun UpdateStatusDto.installKind(): UpdateInstallKind = when (capability) {
    "DOWNLOAD_AND_LAUNCH" -> UpdateInstallKind.DownloadAndLaunch
    "IN_PLACE_REPLACE" -> UpdateInstallKind.InPlace
    else -> UpdateInstallKind.NotifyOnly
}
