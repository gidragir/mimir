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

    /**
     * Обе стороны есть, а метаданных не хватает для решения: содержимое сравнивается при исполнении,
     * один раз (спека sync-io §4). Исход — только запись индекса, `Upload` или конфликт с копией.
     */
    data class Reconcile(
        override val path: String,
        val localEntry: LocalEntry,
        val remoteEntry: RemoteEntry,
        val baseRecord: FileSyncRecord?,
    ) : SyncAction

    data class UpdateMetadata(
        override val path: String,
        val record: FileSyncRecord,
    ) : SyncAction

    /**
     * Файла нет ни локально, ни в облаке, а запись индекса осталась (например, процесс упал после
     * удаления, но до сохранения индекса). Запись удаляется в фазе метаданных, без IO: иначе файл,
     * позже восстановленный с тем же содержимым, план принял бы за удалённый в облаке (`DeleteLocal`).
     */
    data class ForgetRecord(override val path: String) : SyncAction

    data class NoOp(override val path: String) : SyncAction
}

data class SyncPlan(
    val actions: List<SyncAction>,
) {
    val hasChanges: Boolean get() = actions.any { it !is SyncAction.NoOp && it !is SyncAction.UpdateMetadata && it !is SyncAction.ForgetRecord }
}
