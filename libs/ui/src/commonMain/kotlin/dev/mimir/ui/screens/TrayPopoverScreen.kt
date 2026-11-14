package dev.mimir.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.mimir.core.BuildConfig
import dev.mimir.core.SyncStatus
import dev.mimir.ui.MimirTokens
import dev.mimir.ui.components.MimirButton
import dev.mimir.ui.components.MimirButtonVariant
import dev.mimir.ui.components.MimirCard
import dev.mimir.ui.components.MimirIcons
import dev.mimir.ui.components.MimirProgressBar
import dev.mimir.ui.components.MimirStatusBadge
import dev.mimir.ui.model.MimirUiModel
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
private fun ModelContent(model: MimirUiModel, onOpenMainWindow: () -> Unit, onQuit: () -> Unit, clock: Clock) {
    val status by model.status.collectAsState()
    val vaultPath by model.vaultPath.collectAsState()
    val isLocked by model.isLocked.collectAsState()
    val needsSetup = status == SyncStatus.NotConfigured || vaultPath == null || isLocked

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(BuildConfig.APP_NAME, style = MaterialTheme.typography.titleMedium)
        MimirStatusBadge(status = status, modifier = Modifier.testTag("popover_status_badge"))
    }

    PopoverVaultSummary(status, vaultPath, needsSetup, isLocked, model.progress, model.lastSyncTime, model.accountEmail, clock)
    PopoverBanners(model.conflicts, model.updateState, onOpenMainWindow)
    PopoverActions(model, status, needsSetup, onOpenMainWindow, onQuit)
}
