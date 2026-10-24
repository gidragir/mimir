package dev.mimir.core

import kotlin.time.Instant

enum class SyncPhase { Scanning, Comparing, Applying, Finished }

enum class SyncItemAction { Upload, Download, DeleteLocal, DeleteRemote, Conflict }

enum class SyncProblemKind { ReadFailed, WriteFailed, DecryptFailed, Unknown }

data class SyncItem(val path: String, val action: SyncItemAction)

data class CompletedItem(val path: String, val action: SyncItemAction, val at: Instant)

data class SyncProblem(
    val path: String,
    val action: SyncItemAction,
    val kind: SyncProblemKind,
    val detail: String,
)

data class SyncProgress(
    val phase: SyncPhase,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val applyingStartedAt: Instant? = null,
    val scanned: Int = 0,
    /** Сколько удалённых папок уже обойдено при сканировании (включая корень). */
    val foldersScanned: Int = 0,
    val total: Int = 0,
    val done: Int = 0,
    val bytesTotal: Long = 0,
    val bytesDone: Long = 0,
    val current: SyncItem? = null,
    val upcoming: List<SyncItem> = emptyList(),
    val completed: List<CompletedItem> = emptyList(),
    val problems: List<SyncProblem> = emptyList(),
) {
    /** Оценка оставшегося времени в секундах; null, если данных пока мало. */
    fun etaSeconds(now: Instant): Long? {
        if (phase != SyncPhase.Applying) return null
        val started = applyingStartedAt ?: return null
        val elapsedMs = (now - started).inWholeMilliseconds
        if (elapsedMs < MIN_ELAPSED_MS || bytesDone <= 0L) return null
        val remaining = (bytesTotal - bytesDone).coerceAtLeast(0L)
        return elapsedMs * remaining / bytesDone / 1000L
    }

    companion object {
        const val UPCOMING_LIMIT = 5
        const val COMPLETED_LIMIT = 200
        const val DELETE_WEIGHT_BYTES = 4096L
        private const val MIN_ELAPSED_MS = 2000L
    }
}
