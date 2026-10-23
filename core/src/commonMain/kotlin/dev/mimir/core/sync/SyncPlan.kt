package dev.mimir.core.sync

import dev.mimir.core.LocalEntry
import dev.mimir.core.RemoteEntry
import dev.mimir.core.RemotePath
import dev.mimir.core.VaultFileId

sealed interface SyncAction {
    val path: String

    data class Upload(
        override val path: String,
        val localEntry: LocalEntry,
        val remotePath: RemotePath,
    ) : SyncAction

    data class Download(
        override val path: String,
        val remoteEntry: RemoteEntry,
    ) : SyncAction

    data class DeleteRemote(
        override val path: String,
        val remotePath: RemotePath,
    ) : SyncAction

    data class DeleteLocal(
        override val path: String,
        val localId: VaultFileId,
    ) : SyncAction

    data class Conflict(
        override val path: String,
        val localEntry: LocalEntry,
        val remoteEntry: RemoteEntry,
        val baseRecord: FileSyncRecord?,
    ) : SyncAction

    data class UpdateMetadata(
        override val path: String,
        val record: FileSyncRecord,
    ) : SyncAction

    data class NoOp(override val path: String) : SyncAction
}

data class SyncPlan(
    val actions: List<SyncAction>,
) {
    val hasChanges: Boolean get() = actions.any { it !is SyncAction.NoOp && it !is SyncAction.UpdateMetadata }
}
