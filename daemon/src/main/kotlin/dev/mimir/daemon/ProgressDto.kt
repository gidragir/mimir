package dev.mimir.daemon

import dev.mimir.core.CompletedItem
import dev.mimir.core.SyncItem
import dev.mimir.core.SyncItemAction
import dev.mimir.core.SyncPhase
import dev.mimir.core.SyncProblem
import dev.mimir.core.SyncProblemKind
import dev.mimir.core.SyncProgress
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class SyncItemDto(val path: String, val action: String)

@Serializable
data class CompletedItemDto(val path: String, val action: String, val at: String)

@Serializable
data class SyncProblemDto(val path: String, val action: String, val kind: String, val detail: String)

@Serializable
data class SyncProgressDto(
    val phase: String,
    val startedAt: String,
    val finishedAt: String? = null,
    val applyingStartedAt: String? = null,
    val scanned: Int = 0,
    val total: Int = 0,
    val done: Int = 0,
    val bytesTotal: Long = 0,
    val bytesDone: Long = 0,
    val etaSeconds: Long? = null,
    val current: SyncItemDto? = null,
    val upcoming: List<SyncItemDto> = emptyList(),
    val completed: List<CompletedItemDto> = emptyList(),
    val problems: List<SyncProblemDto> = emptyList(),
)

private fun Enum<*>.snake(): String =
    name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()

private inline fun <reified E : Enum<E>> parseSnake(value: String): E =
    enumValues<E>().first { it.snake() == value }

private fun SyncItem.toDto() = SyncItemDto(path, action.snake())
private fun SyncItemDto.toModel() = SyncItem(path, parseSnake<SyncItemAction>(action))

fun SyncProgress.toDto(now: Instant): SyncProgressDto = SyncProgressDto(
    phase = phase.snake(),
    startedAt = startedAt.toString(),
    finishedAt = finishedAt?.toString(),
    applyingStartedAt = applyingStartedAt?.toString(),
    scanned = scanned,
    total = total,
    done = done,
    bytesTotal = bytesTotal,
    bytesDone = bytesDone,
    etaSeconds = etaSeconds(now),
    current = current?.toDto(),
    upcoming = upcoming.map { it.toDto() },
    completed = completed.map { CompletedItemDto(it.path, it.action.snake(), it.at.toString()) },
    problems = problems.map { SyncProblemDto(it.path, it.action.snake(), it.kind.snake(), it.detail) },
)

fun SyncProgressDto.toModel(): SyncProgress = SyncProgress(
    phase = parseSnake<SyncPhase>(phase),
    startedAt = Instant.parse(startedAt),
    finishedAt = finishedAt?.let(Instant::parse),
    applyingStartedAt = applyingStartedAt?.let(Instant::parse),
    scanned = scanned,
    total = total,
    done = done,
    bytesTotal = bytesTotal,
    bytesDone = bytesDone,
    current = current?.toModel(),
    upcoming = upcoming.map { it.toModel() },
    completed = completed.map { CompletedItem(it.path, parseSnake<SyncItemAction>(it.action), Instant.parse(it.at)) },
    problems = problems.map {
        SyncProblem(it.path, parseSnake<SyncItemAction>(it.action), parseSnake<SyncProblemKind>(it.kind), it.detail)
    },
)
