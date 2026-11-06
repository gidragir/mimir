package dev.mimir.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.mimir.core.BuildConfig
import dev.mimir.ui.MimirTokens
import dev.mimir.ui.components.MimirButton
import dev.mimir.ui.components.MimirButtonVariant
import dev.mimir.ui.components.MimirCard
import dev.mimir.ui.components.MimirProgressBar
import dev.mimir.ui.model.UpdateRetry
import dev.mimir.ui.model.UpdateTexts
import dev.mimir.ui.model.UpdateUiState

/**
 * Блок «О программе»: версия и всё про обновление. Ничего не блокирует и не всплывает окнами; во время
 * синхронизации установка недоступна (подсказка вместо ошибки), а открыть страницу релиза можно всегда.
 */
@Composable
fun AboutUpdateCard(
    state: UpdateUiState,
    syncRunning: Boolean,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenReleasePage: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    currentVersion: String = BuildConfig.VERSION,
) {
    if (state == UpdateUiState.Hidden) return
    MimirCard(modifier = modifier.fillMaxWidth().testTag("update_card")) {
        Column(
            modifier = Modifier.padding(MimirTokens.Space16),
            verticalArrangement = Arrangement.spacedBy(MimirTokens.Space12),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(UpdateTexts.TITLE, style = MaterialTheme.typography.titleMedium)
                Text(
                    "v${currentVersion.removePrefix("v")}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (state) {
                UpdateUiState.Hidden -> {}
                UpdateUiState.Disabled -> Muted(UpdateTexts.DISABLED_NOTE)
                is UpdateUiState.UpToDate -> {
                    Muted(UpdateTexts.upToDate(state.version))
                    MimirButton(UpdateTexts.CHECK, onCheck, variant = MimirButtonVariant.Secondary)
                }
                is UpdateUiState.Unknown -> {
                    Muted(UpdateTexts.NOT_CHECKED)
                    MimirButton(UpdateTexts.CHECK, onCheck, variant = MimirButtonVariant.Secondary)
                }
                UpdateUiState.Checking -> {
                    MimirProgressBar(progress = null, modifier = Modifier.fillMaxWidth().testTag("update_progress"))
                    Muted(UpdateTexts.CHECKING)
                }
                is UpdateUiState.Available -> AvailableBlock(state, syncRunning, onDownload, onOpenReleasePage, onDismiss)
                is UpdateUiState.Downloading -> {
                    val percent = (state.progress * 100).toInt().coerceIn(0, 100)
                    MimirProgressBar(state.progress, Modifier.fillMaxWidth().testTag("update_progress"))
                    Text(UpdateTexts.downloading(state.version, percent), style = MaterialTheme.typography.bodyMedium)
                }
                is UpdateUiState.ReadyToInstall -> {
                    Text(UpdateTexts.readyToInstall(state.version), style = MaterialTheme.typography.bodyMedium)
                    InstallButton(UpdateTexts.INSTALL_NOW, syncRunning, onInstall)
                }
                is UpdateUiState.Installing -> {
                    MimirProgressBar(progress = null, modifier = Modifier.fillMaxWidth().testTag("update_progress"))
                    Text(UpdateTexts.INSTALLING, style = MaterialTheme.typography.bodyMedium)
                    Muted(UpdateTexts.INSTALLING_HINT)
                }
                is UpdateUiState.Error -> {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MimirTokens.StatusError)
                    Row(horizontalArrangement = Arrangement.spacedBy(MimirTokens.Space8)) {
                        val retry = when (state.retry) {
                            UpdateRetry.Check -> onCheck
                            UpdateRetry.Download -> onDownload
                            UpdateRetry.Install -> onInstall
                        }
                        MimirButton(UpdateTexts.RETRY, retry, variant = MimirButtonVariant.Primary)
                        MimirButton(UpdateTexts.DISMISS, onDismiss, variant = MimirButtonVariant.Ghost)
                    }
                    state.details?.let { ErrorDetails(it) }
                }
            }
        }
    }
}

@Composable
private fun AvailableBlock(
    state: UpdateUiState.Available,
    syncRunning: Boolean,
    onDownload: () -> Unit,
    onOpenReleasePage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Text(
        UpdateTexts.available(state.version),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(MimirTokens.Radius6))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .padding(horizontal = MimirTokens.Space8, vertical = MimirTokens.Space4)
            .testTag("update_badge"),
    )
    state.notes?.let { NotesBlock(it) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(MimirTokens.Space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val label = UpdateTexts.primaryAction(state.kind)
        if (state.canInstallInApp) {
            InstallButton(label, syncRunning, onDownload, showHint = false)
        } else {
            // Страницу релиза можно открыть и во время синхронизации.
            MimirButton(label, { state.releaseUrl?.let(onOpenReleasePage) }, enabled = state.releaseUrl != null)
        }
        MimirButton(UpdateTexts.DISMISS, onDismiss, variant = MimirButtonVariant.Ghost)
    }
    if (state.canInstallInApp && syncRunning) Muted(UpdateTexts.SYNC_RUNNING_HINT)
}

/** Главная кнопка установки: во время синхронизации серая, подсказка — под ней. */
@Composable
private fun InstallButton(text: String, syncRunning: Boolean, onClick: () -> Unit, showHint: Boolean = true) {
    MimirButton(text, onClick, enabled = !syncRunning, variant = MimirButtonVariant.Primary)
    if (showHint && syncRunning) Muted(UpdateTexts.SYNC_RUNNING_HINT)
}

@Composable
private fun NotesBlock(notes: String) {
    Column(verticalArrangement = Arrangement.spacedBy(MimirTokens.Space4)) {
        Text(UpdateTexts.WHATS_NEW, style = MaterialTheme.typography.labelLarge)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MimirTokens.Radius6))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .heightIn(max = 160.dp)
                .verticalScroll(rememberScrollState())
                .padding(MimirTokens.Space12)
                .testTag("update_notes"),
        ) {
            Text(notes, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Исходный текст ошибки для поддержки: свёрнут по умолчанию, раскрывается в моноширинном блоке с выделением
 * (можно скопировать). Сворачивается заново, когда приходит другая ошибка.
 */
@Composable
private fun ErrorDetails(details: String) {
    var expanded by remember(details) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(MimirTokens.Space4)) {
        MimirButton(UpdateTexts.details(expanded), { expanded = !expanded }, variant = MimirButtonVariant.Ghost)
        if (expanded) {
            Muted(UpdateTexts.DETAILS_HINT)
            SelectionContainer {
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(MimirTokens.Radius6))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .heightIn(max = 120.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(MimirTokens.Space8)
                        .testTag("update_error_details"),
                )
            }
        }
    }
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
