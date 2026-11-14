package dev.mimir.core.sync

import dev.mimir.core.LocalEntry
import dev.mimir.core.RemoteAuthException
import dev.mimir.core.RemoteEntry
import dev.mimir.core.RemotePath
import dev.mimir.core.RemoteUnavailableException
import dev.mimir.core.rules.SyncRules
import kotlin.coroutines.cancellation.CancellationException

/**
 * Решение по метаданным и хэшу локального файла (спека sync-io §3). Удалённое содержимое здесь
 * не читается никогда: где метаданных не хватает, план выдаёт [SyncAction.Reconcile], и содержимое
 * сравнивается один раз при исполнении.
 */
class SyncPlanBuilder(
    private val rules: SyncRules = SyncRules(),
) {
    /**
     * [localHash] — SHA-256 локального файла (потоком); вызывается только там, где решение от него зависит.
     * [onReconcileFailure] получает ошибки [localHash]: путь пропускается (`NoOp`), план строится дальше.
     * Без него ошибка пробрасывается.
     */
    suspend fun buildPlan(
        local: List<LocalEntry>,
        remote: List<RemoteEntry>,
        index: SyncIndex,
        localHash: suspend (LocalEntry) -> String,
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
            val action = try {
                decide(path, localMap[path], remoteMap[path], index.find(path), localHash)
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

    private suspend fun decide(
        path: String,
        localEntry: LocalEntry?,
        remoteEntry: RemoteEntry?,
        base: FileSyncRecord?,
        localHash: suspend (LocalEntry) -> String,
    ): SyncAction {
        if (base == null) {
            return when {
                localEntry != null && remoteEntry != null -> SyncAction.Reconcile(path, localEntry, remoteEntry, null)
                localEntry != null -> SyncAction.Upload(path, localEntry, RemotePath(path))
                remoteEntry != null -> SyncAction.Download(path, remoteEntry)
                else -> SyncAction.NoOp(path)
            }
        }

        return when {
            localEntry != null && remoteEntry != null -> {
                val localChanged = localChanged(localEntry, base)
                val remoteChanged = remoteChanged(remoteEntry, base)
                when {
                    !localChanged && !remoteChanged ->
                        if (remoteEntry.modifiedAt != base.remoteModifiedAt) {
                            SyncAction.UpdateMetadata(path, base.copy(remoteModifiedAt = remoteEntry.modifiedAt))
                        } else {
                            SyncAction.NoOp(path)
                        }

                    localChanged && !remoteChanged ->
                        if (localHash(localEntry) == base.baseHash) {
                            val record = base.copy(
                                localSize = localEntry.size,
                                localModifiedAt = localEntry.modifiedAt,
                                remoteModifiedAt = remoteEntry.modifiedAt,
                            )
                            SyncAction.UpdateMetadata(path, record)
                        } else {
                            SyncAction.Upload(path, localEntry, remoteEntry.path)
                        }

                    !localChanged && remoteChanged -> SyncAction.Download(path, remoteEntry)

                    else ->
                        if (localHash(localEntry) == base.baseHash) {
                            SyncAction.Download(path, remoteEntry)
                        } else {
                            SyncAction.Reconcile(path, localEntry, remoteEntry, base)
                        }
                }
            }

            // Локальный удалён. Изменённый удалённый воскрешается: ошибка в сторону сохранения файла.
            remoteEntry != null ->
                if (remoteChanged(remoteEntry, base)) {
                    SyncAction.Download(path, remoteEntry)
                } else {
                    SyncAction.DeleteRemote(path, remoteEntry.path)
                }

            // Удалённый удалён. Изменённый локальный отправляется заново; при исполнении DeleteLocal — проверка stat.
            localEntry != null ->
                if (localChanged(localEntry, base) && localHash(localEntry) != base.baseHash) {
                    SyncAction.Upload(path, localEntry, RemotePath(path))
                } else {
                    SyncAction.DeleteLocal(path, localEntry.id)
                }

            // Нет нигде, запись осталась — устаревшая: забыть, иначе восстановленный файл уйдёт в DeleteLocal.
            else -> SyncAction.ForgetRecord(path)
        }
    }

    private fun localChanged(entry: LocalEntry, base: FileSyncRecord): Boolean =
        entry.size != base.localSize || entry.modifiedAt != base.localModifiedAt

    /**
     * Ревизия (у Drive — md5 шифротекста, новый при каждой записи) есть у обеих сторон — сравниваются
     * ревизия и размер, а смена одного mtime изменением не считается. Иначе — размер и mtime.
     */
    private fun remoteChanged(entry: RemoteEntry, base: FileSyncRecord): Boolean {
        val revision = entry.revision
        val baseRevision = base.remoteRevision
        return if (revision != null && baseRevision != null) {
            revision != baseRevision || entry.size != base.remoteSize
        } else {
            entry.size != base.remoteSize || entry.modifiedAt != base.remoteModifiedAt
        }
    }

    /** Путь под скрытым vault путём (например, символьной ссылкой): его отсутствие в локальном списке ничего не значит. */
    private fun isHidden(path: String, hidden: Set<String>): Boolean {
        if (hidden.isEmpty()) return false
        if (path in hidden) return true
        var idx = path.indexOf('/')
        while (idx >= 0) {
            if (path.substring(0, idx) in hidden) return true
            idx = path.indexOf('/', idx + 1)
        }
        return false
    }

    private fun normalize(path: String): String =
        path.replace('\\', '/').split('/').filter { it.isNotEmpty() }.joinToString("/")
}
