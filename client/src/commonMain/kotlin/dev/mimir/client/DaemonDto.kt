package dev.mimir.client

import dev.mimir.core.CompletedItem
import dev.mimir.core.SyncItem
import dev.mimir.core.SyncItemAction
import dev.mimir.core.SyncPhase
import dev.mimir.core.SyncProblem
import dev.mimir.core.SyncProblemKind
import dev.mimir.core.SyncProgress
import kotlinx.serialization.Serializable
import kotlin.time.Instant

private fun Enum<*>.snake(): String =
    name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()

private inline fun <reified E : Enum<E>> parseSnake(value: String): E =
    enumValues<E>().firstOrNull { it.snake() == value } ?: enumValues<E>().first()

fun DaemonSyncProgressDto.toModel(): SyncProgress = SyncProgress(
    phase = parseSnake<SyncPhase>(phase),
    startedAt = Instant.parse(startedAt),
    finishedAt = finishedAt?.let(Instant::parse),
    applyingStartedAt = applyingStartedAt?.let(Instant::parse),
    scanned = scanned,
    foldersScanned = foldersScanned,
    total = total,
    done = done,
    bytesTotal = bytesTotal,
    bytesDone = bytesDone,
    current = current?.let { SyncItem(it.path, parseSnake<SyncItemAction>(it.action)) },
    upcoming = upcoming.map { SyncItem(it.path, parseSnake<SyncItemAction>(it.action)) },
    completed = completed.map { CompletedItem(it.path, parseSnake<SyncItemAction>(it.action), Instant.parse(it.at)) },
    problems = problems.map {
        SyncProblem(it.path, parseSnake<SyncItemAction>(it.action), parseSnake<SyncProblemKind>(it.kind), it.detail)
    },
)

@Serializable
data class DaemonStatusDto(
    val status: String,
    val label: String,
    val vaultPath: String? = null,
    val lastSyncAt: String? = null,
    val isLocked: Boolean,
    val activeConflicts: Int,
    val accountEmail: String? = null,
    val progress: DaemonSyncProgressDto? = null,
)

@Serializable
data class DaemonSyncProgressDto(
    val phase: String,
    val startedAt: String,
    val finishedAt: String? = null,
    val applyingStartedAt: String? = null,
    val scanned: Int = 0,
    val foldersScanned: Int = 0,
    val total: Int = 0,
    val done: Int = 0,
    val bytesTotal: Long = 0,
    val bytesDone: Long = 0,
    val etaSeconds: Long? = null,
    val current: DaemonSyncItemDto? = null,
    val upcoming: List<DaemonSyncItemDto> = emptyList(),
    val completed: List<DaemonCompletedItemDto> = emptyList(),
    val problems: List<DaemonSyncProblemDto> = emptyList(),
)

@Serializable
data class DaemonSyncItemDto(
    val path: String,
    val action: String,
)

@Serializable
data class DaemonCompletedItemDto(
    val path: String,
    val action: String,
    val at: String,
)

@Serializable
data class DaemonSyncProblemDto(
    val path: String,
    val action: String,
    val kind: String,
    val detail: String,
)

@Serializable
data class DaemonConflictItemDto(
    val originalPath: String,
    val conflictPath: String,
    val createdAt: String,
)

@Serializable
data class DaemonConflictsResponse(
    val conflicts: List<DaemonConflictItemDto>,
)

@Serializable
data class DaemonResolveConflictRequest(
    val originalPath: String,
    val conflictPath: String,
    val resolution: String, // "keep_original" | "keep_conflict"
)

@Serializable
data class DaemonResolveConflictResponse(
    val status: String,
    val path: String,
)

@Serializable
data class DaemonUnlockRequest(
    val password: String,
)

@Serializable
data class DaemonVaultRequest(
    val vaultPath: String,
)

@Serializable
data class DaemonVaultConfigResponse(
    val vaultPath: String,
    val fileCount: Int,
)

@Serializable
data class DaemonVaultInspectionDto(
    val localFileCount: Int,
    val remoteFileCount: Int,
    val requiresStrategyConfirmation: Boolean,
)

@Serializable
data class DaemonDeviceCodeDto(
    @kotlinx.serialization.SerialName("device_code")
    val deviceCode: String,
    @kotlinx.serialization.SerialName("user_code")
    val userCode: String,
    @kotlinx.serialization.SerialName("verification_url")
    val verificationUrl: String,
    @kotlinx.serialization.SerialName("expires_in")
    val expiresInSeconds: Long,
    @kotlinx.serialization.SerialName("interval")
    val intervalSeconds: Long,
)

@Serializable
data class DaemonAuthUrlResponse(
    val authUrl: String,
)

@Serializable
data class DaemonMethodsResponse(
    val methods: List<String>,
)

@Serializable
data class DaemonStatusOnlyResponse(
    val status: String,
)

@Serializable
data class DaemonAuthorizedResponse(
    val status: String,
    val email: String?,
)

@Serializable
data class DaemonExpiredResponse(
    val status: String,
    val error: String,
)

@Serializable
data class DaemonFailedResponse(
    val status: String,
    val error: String,
)

@Serializable
data class DaemonErrorBody(
    val error: String,
)

enum class DaemonConnectionStatus {
    Connecting,
    Connected,
    Reconnecting,
    Disconnected,
}

class InvalidPasswordException(message: String) : IllegalArgumentException(message)
class SyncNotConfiguredException(message: String) : IllegalStateException(message)

enum class DaemonLoginMethod {
    Browser,
    DeviceCode,
}

sealed interface DaemonDevicePoll {
    data object Pending : DaemonDevicePoll
    data class Authorized(val email: String?) : DaemonDevicePoll
    data class Expired(val reason: String) : DaemonDevicePoll
}

sealed interface DaemonBrowserLoginPoll {
    data object Pending : DaemonBrowserLoginPoll
    data class Authorized(val email: String?) : DaemonBrowserLoginPoll
    data class Failed(val reason: String) : DaemonBrowserLoginPoll
}
