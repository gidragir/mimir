package dev.mimir.client

import kotlinx.serialization.Serializable

@Serializable
data class DaemonStatusDto(
    val status: String,
    val label: String,
    val vaultPath: String?,
    val lastSyncAt: String?,
    val isLocked: Boolean,
    val activeConflicts: Int,
    val accountEmail: String?,
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
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresInSeconds: Long,
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
data class DaemonErrorBody(
    val error: String,
)

enum class DaemonConnectionStatus {
    Connected,
    Reconnecting,
    Disconnected,
}

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
