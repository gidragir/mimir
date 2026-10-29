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
import dev.mimir.core.SyncErrorKind
import dev.mimir.core.SyncStatus
import dev.mimir.core.rules.SyncRules
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

class DefaultSyncEngine(
    private val localVault: LocalVault,
    private val remoteStorage: RemoteStorage,
    private val stateStore: SyncStateStore,
    private val conflictResolver: ConflictResolver = DefaultConflictResolver(),
    private val rules: SyncRules = SyncRules(),
    private val clock: Clock = Clock.System,
    private val parallelism: Int = 4,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val indexSaveEvery: Int = 100,
) : SyncEngine {

    init {
        require(parallelism in 1..16) { "parallelism must be from 1 to 16, got $parallelism" }
    }

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    override val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _progress = MutableStateFlow<SyncProgress?>(null)
    override val progress: StateFlow<SyncProgress?> = _progress.asStateFlow()

    private val syncMutex = Mutex()
    private val planBuilder = SyncPlanBuilder(rules)
    private val executor = ActionExecutor(localVault, remoteStorage, conflictResolver, dispatcher)

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
                    is RemoteAuthException -> SyncStatus.Error(e.message ?: "Authentication failed", SyncErrorKind.SignInRequired)
                    is SyncAbortedException -> SyncStatus.Error(e.message ?: "Sync aborted", SyncErrorKind.Aborted)
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

        val index = IndexUpdater(stateStore, stateStore.load(), indexSaveEvery)
        val localFiles = localVault.list()
        val hiddenPaths = localVault.hiddenPaths()
        _progress.update { it?.copy(scanned = localFiles.size) }
        val remoteFiles = remoteStorage.listAll(RemotePath("")) { folders, files ->
            _progress.update { it?.copy(foldersScanned = folders, scanned = localFiles.size + files) }
        }

        // Пустой удалённый список при непустом индексе почти наверняка означает сбой
        // (отстающий поиск Drive, пустой дубль папки хранилища, чужой аккаунт), а не то,
        // что пользователь удалил всё. Иначе план превратил бы это в удаление всех локальных файлов.
        if (remoteFiles.isEmpty() && index.current.records.isNotEmpty()) {
            throw SyncAbortedException(EMPTY_REMOTE_MESSAGE)
        }

        _progress.update { it?.copy(phase = SyncPhase.Comparing) }
        val plan = planBuilder.buildPlan(localFiles, remoteFiles, index.current, executor::localHash, hiddenPaths) { path, error ->
            val problem = problemOf(path, SyncItemAction.Upload, error)
            _progress.update { it?.copy(problems = it.problems + problem) }
        }

        var failure: Throwable? = null
        try {
            applyPlan(plan, index)
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            // Отмена или сбой посреди переносов: всё уже выполненное должно попасть в индекс.
            withContext(NonCancellable) {
                try {
                    index.flush()
                } catch (e: Throwable) {
                    failure?.addSuppressed(e) ?: throw e
                }
            }
        }
    }

    /**
     * Спека §6: метаданные (`UpdateMetadata`, `ForgetRecord`) — сразу в индекс, без IO, затем переносы, затем удаления (переименование =
     * удаление + отправка: новая копия появляется до удаления старой). Каждая фаза — [parallelism]
     * воркеров над общей очередью.
     */
    private suspend fun applyPlan(plan: SyncPlan, index: IndexUpdater) {
        val metadata = mutableListOf<(SyncIndex) -> SyncIndex>()
        val transfers = mutableListOf<SyncAction>()
        val deletes = mutableListOf<SyncAction>()
        // Исчерпывающий when: новое действие плана не потеряется молча ни в одной фазе.
        for (action in plan.actions) {
            when (action) {
                is SyncAction.UpdateMetadata -> metadata += { it.withRecord(action.record) }
                is SyncAction.ForgetRecord -> metadata += { it.withoutRecord(action.path) }
                is SyncAction.Upload, is SyncAction.Download, is SyncAction.Reconcile -> transfers += action
                is SyncAction.DeleteRemote, is SyncAction.DeleteLocal -> deletes += action
                is SyncAction.NoOp -> Unit
            }
        }
        val counted = (transfers + deletes).mapNotNull { action -> action.toItemAction()?.let { SyncItem(action.path, it) } }
        _progress.update {
            it?.copy(
                phase = SyncPhase.Applying,
                applyingStartedAt = clock.now(),
                total = counted.size,
                bytesTotal = plan.actions.sumOf { action -> action.weight() },
                upcoming = counted.take(SyncProgress.UPCOMING_LIMIT),
            )
        }

        for (change in metadata) index.update(change)
        runPhase(transfers, offset = 0, counted, index)
        runPhase(deletes, offset = transfers.size, counted, index)
    }

    /**
     * Ошибка воркера, которую он не превратил в проблему (отмена, нет сети, нет доступа, сбой записи
     * индекса), отменяет остальных через [coroutineScope] и пробрасывается.
     */
    private suspend fun runPhase(actions: List<SyncAction>, offset: Int, counted: List<SyncItem>, index: IndexUpdater) {
        if (actions.isEmpty()) return
        val queue = Channel<Int>(Channel.UNLIMITED)
        actions.indices.forEach { queue.trySend(it) }
        queue.close()
        coroutineScope {
            repeat(minOf(parallelism, actions.size)) {
                launch {
                    for (i in queue) applyAction(actions[i], offset + i, counted, index)
                }
            }
        }
    }

    private suspend fun applyAction(action: SyncAction, position: Int, counted: List<SyncItem>, index: IndexUpdater) {
        val item = counted[position]
        val upcoming = counted.subList(position + 1, minOf(position + 1 + SyncProgress.UPCOMING_LIMIT, counted.size)).toList()
        _progress.update { it?.copy(current = item, upcoming = upcoming) }

        var problem: SyncProblem? = null
        val result = try {
            executor.execute(action, index.current)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteUnavailableException) {
            throw e
        } catch (e: RemoteAuthException) {
            throw e
        } catch (e: Exception) {
            problem = problemOf(action.path, item.action, e)
            null
        }
        if (result != null && result.changesIndex) index.update(result::applyTo)

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
                problems = if (problem != null) p.problems + problem else p.problems,
            )
        }
    }

    /** Пункт прогресса; `Reconcile` показывается как сверка, его исход — в `completed`. */
    private fun SyncAction.toItemAction(): SyncItemAction? = when (this) {
        is SyncAction.Upload -> SyncItemAction.Upload
        is SyncAction.Download -> SyncItemAction.Download
        is SyncAction.DeleteLocal -> SyncItemAction.DeleteLocal
        is SyncAction.DeleteRemote -> SyncItemAction.DeleteRemote
        is SyncAction.Reconcile -> SyncItemAction.Verify
        is SyncAction.UpdateMetadata, is SyncAction.ForgetRecord, is SyncAction.NoOp -> null
    }

    private fun SyncAction.weight(): Long = when (this) {
        is SyncAction.Upload -> localEntry.size
        is SyncAction.Download -> remoteEntry.size
        is SyncAction.Reconcile -> localEntry.size + remoteEntry.size
        is SyncAction.DeleteLocal, is SyncAction.DeleteRemote -> SyncProgress.DELETE_WEIGHT_BYTES
        is SyncAction.UpdateMetadata, is SyncAction.ForgetRecord, is SyncAction.NoOp -> 0L
    }

    private companion object {
        const val EMPTY_REMOTE_MESSAGE =
            "Список файлов в Google Drive пуст, хотя раньше там были файлы. Синхронизация остановлена, " +
                "чтобы не удалить ваши файлы на этом компьютере. Проверьте папку в Google Drive и аккаунт."
    }
}
