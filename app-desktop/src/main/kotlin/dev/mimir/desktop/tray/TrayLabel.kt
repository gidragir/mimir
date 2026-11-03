package dev.mimir.desktop.tray

import dev.mimir.core.SyncPhase
import dev.mimir.ui.model.UpdateUiState
import dev.mimir.ui.model.UpdateTexts
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import dev.mimir.core.label

/** Подпись пункта статуса в трее: статус синхронизации плюс счётчики и число проблем прошедшего запуска. */
fun trayLabel(status: SyncStatus, progress: SyncProgress?): String = when {
    status is SyncStatus.Syncing && progress != null && progress.phase == SyncPhase.Applying && progress.total > 0 ->
        "Mimir: syncing ${progress.done}/${progress.total}"
    status is SyncStatus.Idle && progress != null && progress.phase == SyncPhase.Finished && progress.problems.isNotEmpty() -> {
        val n = progress.problems.size
        "Mimir: up to date ($n ${if (n == 1) "problem" else "problems"})"
    }
    else -> status.label()
}

/** Подпись пункта обновления в трее: только пока есть что предложить (доступно или скачано), иначе `null`. */
fun trayUpdateLabel(state: UpdateUiState): String? = when (state) {
    is UpdateUiState.Available -> UpdateTexts.available(state.version)
    is UpdateUiState.ReadyToInstall -> UpdateTexts.available(state.version)
    else -> null
}
