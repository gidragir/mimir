package dev.mimir.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.mimir.core.SyncPhase
import dev.mimir.core.SyncProblem
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import dev.mimir.ui.MimirTokens
import dev.mimir.ui.components.MimirButton
import dev.mimir.ui.components.MimirButtonVariant
import dev.mimir.ui.components.MimirCard
import dev.mimir.ui.components.MimirProgressBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.Instant

@Composable
internal fun StatusPhaseTitle(
    status: SyncStatus,
    progressFlow: StateFlow<SyncProgress?>,
    needsSignIn: Boolean,
) {
    val title = if (status == SyncStatus.Syncing) {
        val progress by remember(progressFlow) {
            progressFlow.distinctUntilChangedBy { it?.phase }
        }.collectAsState(progressFlow.value)
        SyncTexts.phaseTitle(status, progress, needsSignIn = needsSignIn)
    } else {
        SyncTexts.phaseTitle(status, null, needsSignIn = needsSignIn)
    }
    if (title != null) {
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
internal fun StatusSyncProgressSection(
    status: SyncStatus,
    progressFlow: StateFlow<SyncProgress?>,
    lastSyncTimeFlow: StateFlow<Instant?>,
    clock: Clock = Clock.System,
) {
    val progress by progressFlow.collectAsState()
    val lastSync by lastSyncTimeFlow.collectAsState()
    val syncing = status == SyncStatus.Syncing

    // «Сейчас» для прошедшего времени и оценки остатка: тикает раз в секунду, пока идёт синхронизация.
    val now by produceState(clock.now(), syncing, clock) {
        value = clock.now()
        while (syncing) {
            delay(1000)
            value = clock.now()
        }
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
}

@Composable
internal fun StatusSyncProblemsSection(progressFlow: StateFlow<SyncProgress?>) {
    val problems by remember(progressFlow) {
        progressFlow.map { it?.problems.orEmpty() }.distinctUntilChanged()
    }.collectAsState(progressFlow.value?.problems.orEmpty())
    if (problems.isNotEmpty()) {
        ProblemsBlock(problems)
    }
}

@Composable
internal fun ProgressBlock(p: SyncProgress, now: Instant) {
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
internal fun ProblemsBlock(problems: List<SyncProblem>) {
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
internal fun Details(detail: String) {
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
internal fun CompletedBlock(p: SyncProgress) {
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
