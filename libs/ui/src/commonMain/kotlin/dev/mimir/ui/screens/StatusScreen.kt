package dev.mimir.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import dev.mimir.core.BuildConfig
import dev.mimir.core.SyncErrorKind
import dev.mimir.core.SyncStatus
import dev.mimir.ui.MimirTheme
import dev.mimir.ui.MimirTokens
import dev.mimir.ui.components.MimirButton
import dev.mimir.ui.components.MimirButtonVariant
import dev.mimir.ui.components.MimirCard
import dev.mimir.ui.components.MimirStatusBadge
import dev.mimir.ui.model.FakeMimirUiModel
import dev.mimir.ui.model.MimirUiModel
import kotlinx.coroutines.launch
import kotlin.time.Clock

@Composable
fun StatusScreen(
    model: MimirUiModel,
    onOpenConflicts: () -> Unit = {},
    onOpenSetup: () -> Unit = {},
    clock: Clock = Clock.System,
) {
    val status by model.status.collectAsState()
    val email by model.accountEmail.collectAsState()
    val conflicts by model.conflicts.collectAsState()
    val vaultPath by model.vaultPath.collectAsState()
    val isLocked by model.isLocked.collectAsState()
    val update by model.updateState.collectAsState()
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val syncing = status == SyncStatus.Syncing

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(MimirTokens.Space24),
        verticalArrangement = Arrangement.spacedBy(MimirTokens.Space16),
    ) {
        // Заголовок приложения и бейдж статуса
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(BuildConfig.APP_NAME, style = MaterialTheme.typography.headlineMedium)
            MimirStatusBadge(status = status, modifier = Modifier.testTag("mimir_status_badge"))
        }

        StatusBanners(model, onOpenSetup)

        val needsSignIn = status is SyncStatus.Error && email == null

        // Основная карточка статуса синхронизации
        MimirCard(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(MimirTokens.Space16),
                verticalArrangement = Arrangement.spacedBy(MimirTokens.Space12),
            ) {
                StatusPhaseTitle(status = status, progressFlow = model.progress, needsSignIn = needsSignIn)

                when (val s = status) {
                    SyncStatus.Offline -> {
                        Text(
                            text = SyncTexts.OFFLINE_HINT,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is SyncStatus.Error -> {
                        // Вход предлагается при любой ошибке: после отзыва/истечения refresh-токена
                        // email остаётся в account.json, и иначе вернуться ко входу было бы нельзя.
                        Details(s.message)
                        if (s.kind != SyncErrorKind.SignInRequired) {
                            MimirButton(
                                text = SyncTexts.SIGN_IN,
                                onClick = onOpenSetup,
                                variant = MimirButtonVariant.Primary,
                            )
                        }
                    }
                    else -> {}
                }

                StatusSyncProgressSection(
                    status = status,
                    progressFlow = model.progress,
                    lastSyncTimeFlow = model.lastSyncTime,
                    clock = clock,
                )

                MimirButton(
                    text = SyncTexts.SYNC_NOW,
                    onClick = { scope.launch { model.triggerSync() } },
                    enabled = status == SyncStatus.Idle || status == SyncStatus.Offline || status is SyncStatus.Error,
                    variant = MimirButtonVariant.Primary,
                )
            }
        }

        StatusSyncProblemsSection(progressFlow = model.progress)

        HorizontalDivider()

        // Информация об учетной записи и фоновой синхронизации
        Column(verticalArrangement = Arrangement.spacedBy(MimirTokens.Space8)) {
            val bgSync by model.backgroundSync.collectAsState()
            bgSync?.let {
                Text(
                    text = SyncTexts.backgroundSyncText(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            email?.let {
                Text(
                    text = "Аккаунт: $it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Второстепенные действия (конфликты, повторная настройка)
        Row(
            horizontalArrangement = Arrangement.spacedBy(MimirTokens.Space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (conflicts.isNotEmpty()) {
                MimirButton(
                    text = "Конфликты: ${conflicts.size}",
                    onClick = onOpenConflicts,
                    variant = MimirButtonVariant.Secondary,
                )
            }
            if (vaultPath == null || email == null || isLocked) {
                MimirButton(
                    text = "Настроить Mimir",
                    onClick = onOpenSetup,
                    variant = MimirButtonVariant.Secondary,
                )
            }
        }

        AboutUpdateCard(
            state = update,
            syncRunning = syncing,
            onCheck = { scope.launch { model.checkForUpdates() } },
            onDownload = { scope.launch { model.downloadUpdate() } },
            onInstall = { scope.launch { model.applyUpdate() } },
            onOpenReleasePage = { url -> runCatching { uriHandler.openUri(url) } },
            onDismiss = model::dismissUpdate,
        )
    }
}

@Preview
@Composable
fun StatusScreenPreview() {
    MimirTheme {
        StatusScreen(FakeMimirUiModel())
    }
}
