package dev.mimir.core.sync

import dev.mimir.core.CompletedItem
import dev.mimir.core.LocalVault
import dev.mimir.core.RemoteAuthException
import dev.mimir.core.RemotePath
import dev.mimir.core.RemoteStorage
import dev.mimir.core.RemoteUnavailableException
import dev.mimir.core.SyncEngine
import dev.mimir.core.SyncItem
import dev.mimir.core.SyncItemAction
import dev.mimir.core.SyncPhase
import dev.mimir.core.SyncProblem
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncAbortedException
import dev.mimir.core.SyncStatus
import dev.mimir.core.rules.SyncRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

class DefaultSyncEngine(
    private val localVault: LocalVault,
    private val remoteStorage: RemoteStorage,
    private val stateStore: SyncStateStore,
    private val conflictResolver: ConflictResolver = DefaultConflictResolver(),
    private val rules: SyncRules = SyncRules(),
    private val clock: Clock = Clock.System,
) : SyncEngine {

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    override val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _progress = MutableStateFlow<SyncProgress?>(null)
    override val progress: StateFlow<SyncProgress?> = _progress.asStateFlow()

    private val syncMutex = Mutex()
    private val planBuilder = SyncPlanBuilder(rules)
    private val executor = ActionExecutor(localVault, remoteStorage, conflictResolver)

    override suspend fun start() = Unit

    override suspend fun stop() = Unit

    override suspend fun syncNow() {
        syncMutex.withLock {
            _status.value = SyncStatus.Syncing
            try {
                performSync()
                _status.value = SyncStatus.Idle
            } catch (e: Throwable) {
                _status.value = when (e) {
                    // Отмена (stop/пауза планировщика) — не ошибка: статус без сбоя.
                    is CancellationException -> SyncStatus.Idle
                    is RemoteUnavailableException -> SyncStatus.Offline
                    else -> SyncStatus.Error(e.message ?: "Unknown sync error")
                }
                throw e
            } finally {
                _progress.update { current ->
                    current?.copy(
                        phase = SyncPhase.Finished,
                        finishedAt = clock.now(),
                        current = null,
                        upcoming = emptyList(),
                    )
                }
            }
        }
    }

    private suspend fun performSync() {
        _progress.value = SyncProgress(phase = SyncPhase.Scanning, startedAt = clock.now())
        val problems = mutableListOf<SyncProblem>()

        var currentIndex = stateStore.load()
        val localFiles = localVault.list()
        val hiddenPaths = localVault.hiddenPaths()
        _progress.update { it?.copy(scanned = localFiles.size) }
        val remoteFiles = remoteStorage.listAll(RemotePath("")) { folders, files ->
            _progress.update { it?.copy(foldersScanned = folders, scanned = localFiles.size + files) }
        }

        // Пустой удалённый список при непустом индексе почти наверняка означает сбой
        // (отстающий поиск Drive, пустой дубль папки хранилища, чужой аккаунт), а не то,
        // что пользователь удалил всё. Иначе план превратил бы это в удаление всех локальных файлов.
        if (remoteFiles.isEmpty() && currentIndex.records.isNotEmpty()) {
            throw SyncAbortedException(EMPTY_REMOTE_MESSAGE)
        }

        _progress.update { it?.copy(phase = SyncPhase.Comparing) }
        val plan = planBuilder.buildPlan(localFiles, remoteFiles, currentIndex, executor::localHash, hiddenPaths) { path, error ->
            problems += problemOf(path, SyncItemAction.Upload, error)
            _progress.update { it?.copy(problems = problems.toList()) }
        }

        val counted = plan.actions.mapNotNull { action -> action.toItemAction()?.let { SyncItem(action.path, it) } }
        _progress.update {
            it?.copy(
                phase = SyncPhase.Applying,
                applyingStartedAt = clock.now(),
                total = counted.size,
                bytesTotal = plan.actions.sumOf { action -> action.weight() },
                upcoming = counted.take(SyncProgress.UPCOMING_LIMIT),
            )
        }

        var position = 0
        for (action in plan.actions) {
            val itemAction = action.toItemAction()
            if (itemAction == null) {
                currentIndex = applySilently(action, currentIndex)
                continue
            }
            val item = counted[position]
            position++
            val upcoming = counted.subList(position, minOf(position + SyncProgress.UPCOMING_LIMIT, counted.size))
            _progress.update { it?.copy(current = item, upcoming = upcoming.toList()) }

            val result = try {
                executor.execute(action, currentIndex)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RemoteUnavailableException) {
                throw e
            } catch (e: RemoteAuthException) {
                throw e
            } catch (e: Exception) {
                problems += problemOf(action.path, itemAction, e)
                null
            }
            if (result != null && result.changesIndex) {
                currentIndex = result.applyTo(currentIndex)
                stateStore.save(currentIndex)
            }

            val completedAs = result?.completedAs
            val finishedAt = clock.now()
            _progress.update { p ->
                p?.copy(
                    done = p.done + 1,
                    bytesDone = p.bytesDone + action.weight(),
                    completed = if (completedAs != null) {
                        (p.completed + CompletedItem(item.path, completedAs, finishedAt))
                            .takeLast(SyncProgress.COMPLETED_LIMIT)
                    } else {
                        p.completed
                    },
                    problems = problems.toList(),
                )
            }
        }
    }

    private suspend fun applySilently(action: SyncAction, index: SyncIndex): SyncIndex = when (action) {
        is SyncAction.UpdateMetadata -> index.withRecord(action.record).also { stateStore.save(it) }
        else -> index
    }

    /** Пункт прогресса; `Reconcile` показывается как сверка, его исход — в `completed`. */
    private fun SyncAction.toItemAction(): SyncItemAction? = when (this) {
        is SyncAction.Upload -> SyncItemAction.Upload
        is SyncAction.Download -> SyncItemAction.Download
        is SyncAction.DeleteLocal -> SyncItemAction.DeleteLocal
        is SyncAction.DeleteRemote -> SyncItemAction.DeleteRemote
        is SyncAction.Reconcile -> SyncItemAction.Verify
        is SyncAction.UpdateMetadata, is SyncAction.NoOp -> null
    }

    private fun SyncAction.weight(): Long = when (this) {
        is SyncAction.Upload -> localEntry.size
        is SyncAction.Download -> remoteEntry.size
        is SyncAction.Reconcile -> localEntry.size + remoteEntry.size
        is SyncAction.DeleteLocal, is SyncAction.DeleteRemote -> SyncProgress.DELETE_WEIGHT_BYTES
        is SyncAction.UpdateMetadata, is SyncAction.NoOp -> 0L
    }

    private companion object {
        const val EMPTY_REMOTE_MESSAGE =
            "Список файлов в Google Drive пуст, хотя раньше там были файлы. Синхронизация остановлена, " +
                "чтобы не удалить ваши файлы на этом компьютере. Проверьте папку в Google Drive и аккаунт."
    }
}
