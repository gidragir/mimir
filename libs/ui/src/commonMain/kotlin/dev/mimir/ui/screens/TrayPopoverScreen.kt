package dev.mimir.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mimir.core.BuildConfig
import dev.mimir.core.SyncPhase
import dev.mimir.core.SyncStatus
import dev.mimir.ui.MimirTokens
import dev.mimir.ui.components.MimirButton
import dev.mimir.ui.components.MimirButtonVariant
import dev.mimir.ui.components.MimirCard
import dev.mimir.ui.components.MimirIcons
import dev.mimir.ui.components.MimirProgressBar
import dev.mimir.ui.components.MimirStatusBadge
import dev.mimir.ui.model.MimirUiModel
import dev.mimir.ui.model.UpdateTexts
import dev.mimir.ui.model.UpdateUiState
import dev.mimir.ui.screens.setup.vaultDisplayName
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Содержимое поповера трея: статус, прогресс, баннеры и действия. [model] == null — демон ещё стартует
 * (или не стартовал: тогда [startupError]).
 */
@Composable
fun TrayPopoverScreen(
    model: MimirUiModel?,
    startupError: String? = null,
    onOpenMainWindow: () -> Unit,
    onQuit: () -> Unit,
    clock: Clock = Clock.System,
    modifier: Modifier = Modifier,
) {
    MimirCard(modifier = modifier.width(320.dp).testTag("tray_popover_root")) {
        Column(
            modifier = Modifier.padding(MimirTokens.Space16),
            verticalArrangement = Arrangement.spacedBy(MimirTokens.Space12),
        ) {
            if (model == null) {
                StartupContent(startupError, onOpenMainWindow, onQuit)
            } else {
                ModelContent(model, onOpenMainWindow, onQuit, clock)
            }
        }
    }
}

@Composable
private fun StartupContent(startupError: String?, onOpenMainWindow: () -> Unit, onQuit: () -> Unit) {
    Text(BuildConfig.APP_NAME, style = MaterialTheme.typography.titleMedium)
    if (startupError != null) {
        Text(
            "Не удалось запустить Mimir: $startupError",
            style = MaterialTheme.typography.bodyMedium,
            color = MimirTokens.StatusError,
        )
    } else {
        MimirProgressBar(progress = null, modifier = Modifier.fillMaxWidth())
        Text("Запуск…", style = MaterialTheme.typography.bodyMedium)
    }
    MimirButton(
        text = "Открыть Mimir",
        onClick = onOpenMainWindow,
        modifier = Modifier.fillMaxWidth(),
        variant = MimirButtonVariant.Secondary,
        icon = { Icon(MimirIcons.ExternalLink, contentDescription = null, modifier = Modifier.size(16.dp)) },
    )
    QuitRow(onQuit)
}

@Composable
private fun QuitRow(onQuit: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        MimirButton(
            text = "Выход",
            onClick = onQuit,
            modifier = Modifier,
            variant = MimirButtonVariant.Ghost,
            icon = { Icon(MimirIcons.Power, contentDescription = null, modifier = Modifier.size(14.dp)) },
        )
    }
}

@Composable
private fun ModelContent(model: MimirUiModel, onOpenMainWindow: () -> Unit, onQuit: () -> Unit, clock: Clock) {
    val status by model.status.collectAsState()
    val progress by model.progress.collectAsState()
    val vaultPath by model.vaultPath.collectAsState()
    val isLocked by model.isLocked.collectAsState()
    val lastSync by model.lastSyncTime.collectAsState()
    val email by model.accountEmail.collectAsState()
    val conflicts by model.conflicts.collectAsState()
    val update by model.updateState.collectAsState()
    val scope = rememberCoroutineScope()

    val syncing = status == SyncStatus.Syncing
    // Секундный тикер живёт только пока идёт синхронизация; скрытый поповер вообще не композируется.
    val now by produceState(clock.now(), syncing, clock) {
        value = clock.now()
        while (syncing) {
            delay(1000)
            value = clock.now()
        }
    }

    val path = vaultPath
    val needsSetup = status == SyncStatus.NotConfigured || path == null || isLocked

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(BuildConfig.APP_NAME, style = MaterialTheme.typography.titleMedium)
        MimirStatusBadge(status = status, modifier = Modifier.testTag("popover_status_badge"))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MimirTokens.Radius6))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(MimirTokens.Space12),
        verticalArrangement = Arrangement.spacedBy(MimirTokens.Space8),
    ) {
        if (path != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MimirTokens.Space8)) {
                Icon(MimirIcons.Folder, contentDescription = null, tint = MimirTokens.Accent, modifier = Modifier.size(16.dp))
                Text(
                    vaultDisplayName(path),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (needsSetup) {
            Text(
                if (isLocked && path != null) "Хранилище заблокировано" else "Хранилище не подключено",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            // Сырое сообщение SyncStatus.Error.message не показываем — только канонический заголовок.
            SyncTexts.phaseTitle(status, progress, needsSignIn = status is SyncStatus.Error && email == null)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (status is SyncStatus.Error) MimirTokens.StatusError else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (syncing) {
                val p = progress
                if (p?.phase == SyncPhase.Applying) {
                    val fraction = if (p.total > 0) p.done.toFloat() / p.total else 0f
                    MimirProgressBar(progress = fraction, modifier = Modifier.fillMaxWidth())
                    Text(SyncTexts.counter(p.done, p.total), style = MaterialTheme.typography.bodySmall)
                    p.current?.let {
                        Text(
                            SyncTexts.actionNow(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    MimirProgressBar(progress = null, modifier = Modifier.fillMaxWidth())
                    p?.let { Text(SyncTexts.scanned(it.scanned, it.foldersScanned), style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (status == SyncStatus.Idle) {
                lastSync?.let {
                    Text(
                        "Последняя синхронизация: ${SyncTexts.whenFinished(it, now)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (status == SyncStatus.Offline) {
                Text(
                    SyncTexts.OFFLINE_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (conflicts.isNotEmpty()) {
        Text(
            SyncTexts.notificationConflict(conflicts.size),
            color = MimirTokens.StatusError,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MimirTokens.Radius6))
                .background(MimirTokens.StatusError.copy(alpha = 0.14f))
                .clickable(onClick = onOpenMainWindow)
                .padding(horizontal = MimirTokens.Space12, vertical = MimirTokens.Space8)
                .testTag("popover_conflicts_banner"),
        )
    }

    val updateVersion = when (val u = update) {
        is UpdateUiState.Available -> u.version
        is UpdateUiState.ReadyToInstall -> u.version
        else -> null
    }
    if (updateVersion != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MimirTokens.Radius6))
                .background(MimirTokens.Accent.copy(alpha = 0.14f))
                .clickable(onClick = onOpenMainWindow)
                .padding(horizontal = MimirTokens.Space12, vertical = MimirTokens.Space8)
                .testTag("popover_update_banner"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MimirTokens.Space8),
        ) {
            Icon(MimirIcons.Sparkles, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Text(
                UpdateTexts.available(updateVersion),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }

    if (!needsSetup) {
        MimirButton(
            text = if (syncing) SyncTexts.SYNCING else SyncTexts.SYNC_NOW,
            onClick = { scope.launch { runCatching { model.triggerSync() } } },
            modifier = Modifier.fillMaxWidth(),
            variant = MimirButtonVariant.Primary,
            loading = syncing,
            enabled = status == SyncStatus.Idle,
            icon = { Icon(MimirIcons.Refresh, contentDescription = null, modifier = Modifier.size(16.dp)) },
        )
    }
    MimirButton(
        text = if (needsSetup) "Настроить Mimir" else "Открыть Mimir",
        onClick = onOpenMainWindow,
        modifier = Modifier.fillMaxWidth(),
        variant = if (needsSetup) MimirButtonVariant.Primary else MimirButtonVariant.Secondary,
        icon = { Icon(MimirIcons.ExternalLink, contentDescription = null, modifier = Modifier.size(16.dp)) },
    )
    QuitRow(onQuit)
}
