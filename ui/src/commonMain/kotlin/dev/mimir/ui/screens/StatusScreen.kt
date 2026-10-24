package dev.mimir.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mimir.core.BuildConfig
import dev.mimir.core.SyncPhase
import dev.mimir.core.SyncProblem
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import dev.mimir.core.label
import dev.mimir.ui.MimirTheme
import dev.mimir.ui.ObsidianStyle
import dev.mimir.ui.ObsidianStyle.compactButton
import dev.mimir.ui.model.FakeMimirUiModel
import dev.mimir.ui.model.MimirUiModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.ui.tooling.preview.Preview
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
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(BuildConfig.APP_NAME, style = MaterialTheme.typography.headlineMedium)

        val needsSignIn = status is SyncStatus.Error && email == null
        val title = SyncTexts.phaseTitle(status, progress, needsSignIn = needsSignIn)
        Text(title ?: status.label(), style = MaterialTheme.typography.titleLarge)

        when (val s = status) {
            SyncStatus.Offline -> Text(SyncTexts.OFFLINE_HINT)
            is SyncStatus.Error -> {
                // Вход предлагается при любой ошибке: после отзыва/истечения refresh-токена
                // email остаётся в account.json, и иначе вернуться ко входу было бы нельзя.
                Details(s.message)
                Button(
                    onClick = onOpenSetup,
                    shape = MaterialTheme.shapes.extraSmall,
                    colors = ObsidianStyle.buttonColors(),
                    contentPadding = ObsidianStyle.buttonPadding,
                    modifier = Modifier.compactButton(),
                ) { Text(SyncTexts.SIGN_IN) }
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
            )
        } else if (p == null) {
            lastSync?.let { Text("Последняя синхронизация: ${SyncTexts.whenFinished(it, now)}") }
        }

        if (p != null && p.problems.isNotEmpty()) ProblemsBlock(p.problems)

        if (p != null && syncing && p.phase == SyncPhase.Applying) {
            p.current?.let {
                Text(SyncTexts.NOW, style = MaterialTheme.typography.labelLarge)
                Text(SyncTexts.actionNow(it))
            }
            if (p.upcoming.isNotEmpty()) {
                val more = p.total - p.done - (if (p.current != null) 1 else 0) - p.upcoming.size
                Text(SyncTexts.upcomingTitle(more), style = MaterialTheme.typography.labelLarge)
                p.upcoming.take(SyncProgress.UPCOMING_LIMIT).forEach { Text(SyncTexts.actionNow(it)) }
            }
        }

        if (p != null && p.completed.isNotEmpty()) CompletedBlock(p)

        Button(
            onClick = { scope.launch { model.triggerSync() } },
            enabled = status == SyncStatus.Idle,
            shape = MaterialTheme.shapes.extraSmall,
            colors = ObsidianStyle.buttonColors(),
            contentPadding = ObsidianStyle.buttonPadding,
            modifier = Modifier.compactButton(),
        ) {
            Text(SyncTexts.SYNC_NOW)
        }

        HorizontalDivider()
        email?.let { Text("Аккаунт: $it") }
        if (conflicts.isNotEmpty()) {
            OutlinedButton(
                onClick = onOpenConflicts,
                shape = MaterialTheme.shapes.extraSmall,
                contentPadding = ObsidianStyle.buttonPadding,
                modifier = Modifier.compactButton(),
            ) { Text("Конфликты: ${conflicts.size}") }
        }
        if (vaultPath == null || email == null || isLocked) {
            OutlinedButton(
                onClick = onOpenSetup,
                shape = MaterialTheme.shapes.extraSmall,
                contentPadding = ObsidianStyle.buttonPadding,
                modifier = Modifier.compactButton(),
            ) { Text("Настроить Mimir") }
        }
    }
}

@Composable
private fun ProgressBlock(p: SyncProgress, now: Instant) {
    val elapsed = SyncTexts.elapsed((now - p.startedAt).inWholeSeconds)
    when (p.phase) {
        SyncPhase.Scanning, SyncPhase.Comparing -> {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(SyncTexts.scanned(p.scanned, p.foldersScanned))
            Text(elapsed)
        }
        SyncPhase.Applying -> {
            val fraction = if (p.total > 0) p.done.toFloat() / p.total else 0f
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(SyncTexts.counter(p.done, p.total))
            val eta = p.etaSeconds(now)
            Text(if (eta != null) "$elapsed · ${SyncTexts.remaining(eta)}" else elapsed)
        }
        SyncPhase.Finished -> {}
    }
}

@Composable
private fun ProblemsBlock(problems: List<SyncProblem>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        border = ObsidianStyle.panelBorder(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(SyncTexts.problemsTitle(problems.size), style = MaterialTheme.typography.titleMedium)
            problems.forEach { problem ->
                Column {
                    Text(problem.path, style = MaterialTheme.typography.bodyLarge)
                    Text(SyncTexts.problemHint(problem.kind))
                    Text(SyncTexts.RETRY_HINT, style = MaterialTheme.typography.bodySmall)
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
    TextButton(onClick = { expanded = !expanded }) { Text(SyncTexts.DETAILS) }
    if (expanded) Text(detail, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun CompletedBlock(p: SyncProgress) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text(SyncTexts.completedTitle(p.completed.size, expanded)) }
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
