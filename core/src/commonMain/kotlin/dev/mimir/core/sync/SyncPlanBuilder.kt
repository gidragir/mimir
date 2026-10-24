package dev.mimir.core.sync

import dev.mimir.core.LocalEntry
import dev.mimir.core.RemoteEntry
import dev.mimir.core.RemoteAuthException
import dev.mimir.core.RemotePath
import dev.mimir.core.RemoteUnavailableException
import dev.mimir.core.rules.SyncRules
import kotlin.coroutines.cancellation.CancellationException

class SyncPlanBuilder(
    private val rules: SyncRules = SyncRules(),
) {
    suspend fun buildPlan(
        local: List<LocalEntry>,
        remote: List<RemoteEntry>,
        index: SyncIndex,
        hashCalculator: suspend (path: String, isLocal: Boolean) -> String,
        hidden: Set<String> = emptySet(),
        onReconcileFailure: ((path: String, error: Throwable) -> Unit)? = null,
    ): SyncPlan {
        val localMap = local.associateBy { normalize(it.relativePath) }
        val remoteMap = remote.filterNot { it.isDirectory }.associateBy { normalize(it.path.value) }

        val allPaths = (localMap.keys + remoteMap.keys + index.records.keys)
            .filterNot { rules.isExcluded(it) || isHidden(it, hidden) }
            .toSet()

        val actions = mutableListOf<SyncAction>()

        for (path in allPaths) {
            val localEntry = localMap[path]
            val remoteEntry = remoteMap[path]
            val baseRecord = index.find(path)

            val action = try {
                reconcile(path, localEntry, remoteEntry, baseRecord, hashCalculator)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RemoteUnavailableException) {
                throw e
            } catch (e: RemoteAuthException) {
                throw e
            } catch (e: Exception) {
                if (onReconcileFailure == null) throw e
                onReconcileFailure(path, e)
                SyncAction.NoOp(path)
            }
            actions.add(action)
        }

        return SyncPlan(actions)
    }

    private suspend fun reconcile(
        path: String,
        localEntry: LocalEntry?,
        remoteEntry: RemoteEntry?,
        baseRecord: FileSyncRecord?,
        hashCalculator: suspend (path: String, isLocal: Boolean) -> String,
    ): SyncAction {
        if (baseRecord == null) {
            return when {
                localEntry != null && remoteEntry == null -> {
                    SyncAction.Upload(path, localEntry, RemotePath(path))
                }
                localEntry == null && remoteEntry != null -> {
                    SyncAction.Download(path, remoteEntry)
                }
                localEntry != null && remoteEntry != null -> {
                    val localHash = hashCalculator(path, true)
                    val remoteHash = hashCalculator(path, false)
                    if (localHash == remoteHash) {
                        val record = FileSyncRecord(
                            relativePath = path,
                            localId = localEntry.id,
                            baseHash = localHash,
                            localSize = localEntry.size,
                            localModifiedAt = localEntry.modifiedAt,
                            remotePath = remoteEntry.path,
                            remoteRevision = remoteEntry.revision,
                            remoteModifiedAt = remoteEntry.modifiedAt,
                            remoteSize = remoteEntry.size,
                        )
                        SyncAction.UpdateMetadata(path, record)
                    } else {
                        SyncAction.Conflict(path, localEntry, remoteEntry, null)
                    }
                }
                else -> SyncAction.NoOp(path)
            }
        }

        return when {
            localEntry != null && remoteEntry != null -> {
                val localChanged = localEntry.size != baseRecord.localSize ||
                    localEntry.modifiedAt != baseRecord.localModifiedAt
                val remoteChanged = remoteEntry.revision != baseRecord.remoteRevision ||
                    remoteEntry.size != baseRecord.remoteSize ||
                    remoteEntry.modifiedAt != baseRecord.remoteModifiedAt

                when {
                    !localChanged && !remoteChanged -> {
                        SyncAction.NoOp(path)
                    }
                    localChanged && !remoteChanged -> {
                        val localHash = hashCalculator(path, true)
                        if (localHash == baseRecord.baseHash) {
                            val record = baseRecord.copy(
                                localSize = localEntry.size,
                                localModifiedAt = localEntry.modifiedAt,
                            )
                            SyncAction.UpdateMetadata(path, record)
                        } else {
                            SyncAction.Upload(path, localEntry, remoteEntry.path)
                        }
                    }
                    !localChanged && remoteChanged -> {
                        val remoteHash = hashCalculator(path, false)
                        if (remoteHash == baseRecord.baseHash) {
                            val record = baseRecord.copy(
                                remoteSize = remoteEntry.size,
                                remoteModifiedAt = remoteEntry.modifiedAt,
                                remoteRevision = remoteEntry.revision,
                            )
                            SyncAction.UpdateMetadata(path, record)
                        } else {
                            SyncAction.Download(path, remoteEntry)
                        }
                    }
                    else -> {
                        val localHash = hashCalculator(path, true)
                        val remoteHash = hashCalculator(path, false)
                        when {
                            localHash == remoteHash -> {
                                val record = FileSyncRecord(
                                    relativePath = path,
                                    localId = localEntry.id,
                                    baseHash = localHash,
                                    localSize = localEntry.size,
                                    localModifiedAt = localEntry.modifiedAt,
                                    remotePath = remoteEntry.path,
                                    remoteRevision = remoteEntry.revision,
                                    remoteModifiedAt = remoteEntry.modifiedAt,
                                    remoteSize = remoteEntry.size,
                                )
                                SyncAction.UpdateMetadata(path, record)
                            }
                            localHash == baseRecord.baseHash -> {
                                SyncAction.Download(path, remoteEntry)
                            }
                            remoteHash == baseRecord.baseHash -> {
                                SyncAction.Upload(path, localEntry, remoteEntry.path)
                            }
                            else -> {
                                SyncAction.Conflict(path, localEntry, remoteEntry, baseRecord)
                            }
                        }
                    }
                }
            }
            localEntry == null && remoteEntry != null -> {
                val remoteChanged = remoteEntry.revision != baseRecord.remoteRevision ||
                    remoteEntry.size != baseRecord.remoteSize ||
                    remoteEntry.modifiedAt != baseRecord.remoteModifiedAt
                if (remoteChanged) {
                    val remoteHash = hashCalculator(path, false)
                    if (remoteHash != baseRecord.baseHash) {
                        SyncAction.Download(path, remoteEntry)
                    } else {
                        SyncAction.DeleteRemote(path, remoteEntry.path)
                    }
                } else {
                    SyncAction.DeleteRemote(path, remoteEntry.path)
                }
            }
            localEntry != null && remoteEntry == null -> {
                val localChanged = localEntry.size != baseRecord.localSize ||
                    localEntry.modifiedAt != baseRecord.localModifiedAt
                if (localChanged) {
                    val localHash = hashCalculator(path, true)
                    if (localHash != baseRecord.baseHash) {
                        SyncAction.Upload(path, localEntry, RemotePath(path))
                    } else {
                        SyncAction.DeleteLocal(path, localEntry.id)
                    }
                } else {
                    SyncAction.DeleteLocal(path, localEntry.id)
                }
            }
            else -> SyncAction.NoOp(path)
        }
    }

    /** Путь под скрытым vault путём (например, символьной ссылкой): его отсутствие в локальном списке ничего не значит. */
    private fun isHidden(path: String, hidden: Set<String>): Boolean =
        hidden.any { path == it || path.startsWith("$it/") }

    private fun normalize(path: String): String =
        path.replace('\\', '/').split('/').filter { it.isNotEmpty() }.joinToString("/")
}
