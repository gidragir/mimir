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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import dev.mimir.core.BuildConfig
import dev.mimir.core.SyncErrorKind
import dev.mimir.core.SyncPhase
import dev.mimir.core.SyncProblem
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import dev.mimir.core.label
import dev.mimir.ui.MimirTheme
import dev.mimir.ui.MimirTokens
import dev.mimir.ui.components.MimirButton
import dev.mimir.ui.components.MimirButtonVariant
import dev.mimir.ui.components.MimirCard
import dev.mimir.ui.components.MimirProgressBar
import dev.mimir.ui.components.MimirStatusBadge
import dev.mimir.ui.model.FakeMimirUiModel
import dev.mimir.ui.model.MimirUiModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.tooling.preview.Preview
import kotlin.time.Clock
import kotlin.time.Instant

@Composable
fun StatusScreen(
    model: MimirUiModel,
    onOpenConflicts: () -> Unit = {},
    onOpenSetup: () -> Unit = {},
    clock: Clock = Clock.System,
) {
    val status by model.status.collectAsState()
    val progress by model.progress.collectAsState()
    val lastSync by model.lastSyncTime.collectAsState()
    val email by model.accountEmail.collectAsState()
    val conflicts by model.conflicts.collectAsState()
    val vaultPath by model.vaultPath.collectAsState()
    val isLocked by model.isLocked.collectAsState()
    val update by model.updateState.collectAsState()
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    val syncing = status == SyncStatus.Syncing
    // «Сейчас» для прошедшего времени и оценки остатка: тикает раз в секунду, пока идёт синхронизация.
    val now by produceState(clock.now(), syncing, clock) {
        value = clock.now()
        while (syncing) {
            delay(1000)
            value = clock.now()
        }
    }

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
        val title = SyncTexts.phaseTitle(status, progress, needsSignIn = needsSignIn)

        // Основная карточка статуса синхронизации
        MimirCard(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(MimirTokens.Space16),
                verticalArrangement = Arrangement.spacedBy(MimirTokens.Space12),
            ) {
                if (title != null) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                }

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

                val p = progress
                if (p != null && syncing && p.phase != SyncPhase.Finished) {
                    ProgressBlock(p, now)
                }
                if (p != null && status == SyncStatus.Idle && p.phase == SyncPhase.Finished) {
                    val finishedAt = p.finishedAt ?: now
                    Text(
                        "${SyncTexts.whenFinished(finishedAt, now)} · " +
                            SyncTexts.finishedSummary(p.done, (finishedAt - p.startedAt).inWholeSeconds),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (p == null) {
                    lastSync?.let {
                        Text(
                            "Последняя синхронизация: ${SyncTexts.whenFinished(it, now)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (p != null && syncing && p.phase == SyncPhase.Applying) {
                    p.current?.let {
                        Text(SyncTexts.NOW, style = MaterialTheme.typography.labelLarge)
                        Text(SyncTexts.actionNow(it), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (p.upcoming.isNotEmpty()) {
                        val more = p.total - p.done - (if (p.current != null) 1 else 0) - p.upcoming.size
                        Text(SyncTexts.upcomingTitle(more), style = MaterialTheme.typography.labelLarge)
                        p.upcoming.take(SyncProgress.UPCOMING_LIMIT).forEach {
                            Text(SyncTexts.actionNow(it), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                if (p != null && p.completed.isNotEmpty()) {
                    CompletedBlock(p)
                }

                MimirButton(
                    text = SyncTexts.SYNC_NOW,
                    onClick = { scope.launch { model.triggerSync() } },
                    enabled = status == SyncStatus.Idle || status == SyncStatus.Offline || status is SyncStatus.Error,
                    variant = MimirButtonVariant.Primary,
                )
            }
        }

        val p = progress
        if (p != null && p.problems.isNotEmpty()) {
            ProblemsBlock(p.problems)
        }

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

@Composable
private fun ProgressBlock(p: SyncProgress, now: Instant) {
    val elapsed = SyncTexts.elapsed((now - p.startedAt).inWholeSeconds)
    when (p.phase) {
        SyncPhase.Scanning, SyncPhase.Comparing -> {
            MimirProgressBar(
                progress = null,
                modifier = Modifier.fillMaxWidth().testTag("mimir_progress_bar"),
            )
            Text(SyncTexts.scanned(p.scanned, p.foldersScanned), style = MaterialTheme.typography.bodyMedium)
            Text(elapsed, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SyncPhase.Applying -> {
            val fraction = if (p.total > 0) p.done.toFloat() / p.total else 0f
            MimirProgressBar(
                progress = fraction,
                modifier = Modifier.fillMaxWidth().testTag("mimir_progress_bar"),
            )
            Text(SyncTexts.counter(p.done, p.total), style = MaterialTheme.typography.bodyMedium)
            val eta = p.etaSeconds(now)
            Text(
                if (eta != null) "$elapsed · ${SyncTexts.remaining(eta)}" else elapsed,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SyncPhase.Finished -> {}
    }
}

@Composable
private fun ProblemsBlock(problems: List<SyncProblem>) {
    MimirCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(MimirTokens.Space16),
            verticalArrangement = Arrangement.spacedBy(MimirTokens.Space8),
        ) {
            Text(
                SyncTexts.problemsTitle(problems.size),
                style = MaterialTheme.typography.titleMedium,
                color = MimirTokens.StatusError,
            )
            problems.forEach { problem ->
                Column(verticalArrangement = Arrangement.spacedBy(MimirTokens.Space4)) {
                    Text(problem.path, style = MaterialTheme.typography.bodyLarge)
                    Text(SyncTexts.problemHint(problem.kind), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        SyncTexts.RETRY_HINT,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Details(problem.detail)
                }
            }
        }
    }
}

/** Технические подробности — только по нажатию «Подробнее». */
@Composable
private fun Details(detail: String) {
    var expanded by remember { mutableStateOf(false) }
    MimirButton(
        text = SyncTexts.DETAILS,
        onClick = { expanded = !expanded },
        variant = MimirButtonVariant.Ghost,
    )
    if (expanded) {
        Text(detail, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CompletedBlock(p: SyncProgress) {
    var expanded by remember { mutableStateOf(false) }
    MimirButton(
        text = SyncTexts.completedTitle(p.completed.size, expanded),
        onClick = { expanded = !expanded },
        variant = MimirButtonVariant.Ghost,
    )
    if (expanded) {
        p.completed.takeLast(SyncProgress.COMPLETED_LIMIT).asReversed().forEach { Text("✓ ${it.path}") }
    }
}

@Preview
@Composable
fun StatusScreenPreview() {
    MimirTheme {
        StatusScreen(FakeMimirUiModel())
    }
}
