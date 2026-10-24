package dev.mimir.core.sync

import dev.mimir.core.CompletedItem
import dev.mimir.core.CryptException
import dev.mimir.core.LocalEntry
import dev.mimir.core.LocalVault
import dev.mimir.core.RemoteAuthException
import dev.mimir.core.RemoteEntry
import dev.mimir.core.RemotePath
import dev.mimir.core.RemoteStorage
import dev.mimir.core.RemoteUnavailableException
import dev.mimir.core.Sha256
import dev.mimir.core.SyncEngine
import dev.mimir.core.SyncItem
import dev.mimir.core.SyncItemAction
import dev.mimir.core.SyncPhase
import dev.mimir.core.SyncProblem
import dev.mimir.core.SyncProblemKind
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import dev.mimir.core.VaultFileId
import dev.mimir.core.rules.SyncRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
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
        _progress.update { it?.copy(scanned = localFiles.size) }
        val remoteFiles = mutableListOf<RemoteEntry>()
        listAllRemote(RemotePath(""), remoteFiles) {
            _progress.update { it?.copy(scanned = localFiles.size + remoteFiles.size) }
        }

        val hashCalculator: suspend (String, Boolean) -> String = { path, isLocal ->
            if (isLocal) {
                val entry = localVault.getEntry(path)
                    ?: throw NoSuchElementException("Local file not found for hash: $path")
                Sha256.digest(readLocal(entry.id)).toHex()
            } else {
                val bytes = remoteStorage.read(RemotePath(path)).readByteArray()
                Sha256.digest(bytes).toHex()
            }
        }

        _progress.update { it?.copy(phase = SyncPhase.Comparing) }
        val plan = planBuilder.buildPlan(localFiles, remoteFiles, currentIndex, hashCalculator) { path, error ->
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

            val succeeded = try {
                currentIndex = apply(action, currentIndex)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: RemoteUnavailableException) {
                throw e
            } catch (e: RemoteAuthException) {
                throw e
            } catch (e: Exception) {
                problems += problemOf(action.path, itemAction, e)
                false
            }

            val finishedAt = clock.now()
            _progress.update { p ->
                p?.copy(
                    done = p.done + 1,
                    bytesDone = p.bytesDone + action.weight(),
                    completed = if (succeeded) {
                        (p.completed + CompletedItem(item.path, item.action, finishedAt))
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

    private suspend fun apply(action: SyncAction, index: SyncIndex): SyncIndex {
        var currentIndex = index
        when (action) {
            is SyncAction.Upload -> {
                val bytes = readLocal(action.localEntry.id)
                val hash = Sha256.digest(bytes).toHex()
                val remoteEntry = remoteStorage.write(action.remotePath, Buffer().also { it.write(bytes) })
                val record = FileSyncRecord(
                    relativePath = action.path,
                    localId = action.localEntry.id,
                    baseHash = hash,
                    localSize = action.localEntry.size,
                    localModifiedAt = action.localEntry.modifiedAt,
                    remotePath = remoteEntry.path,
                    remoteRevision = remoteEntry.revision,
                    remoteModifiedAt = remoteEntry.modifiedAt,
                    remoteSize = remoteEntry.size,
                )
                currentIndex = currentIndex.withRecord(record)
                stateStore.save(currentIndex)
            }

            is SyncAction.Download -> {
                val bytes = remoteStorage.read(action.remoteEntry.path).readByteArray()
                val hash = Sha256.digest(bytes).toHex()
                val localEntry = writeLocal(action.path, bytes)
                val record = FileSyncRecord(
                    relativePath = action.path,
                    localId = localEntry.id,
                    baseHash = hash,
                    localSize = localEntry.size,
                    localModifiedAt = localEntry.modifiedAt,
                    remotePath = action.remoteEntry.path,
                    remoteRevision = action.remoteEntry.revision,
                    remoteModifiedAt = action.remoteEntry.modifiedAt,
                    remoteSize = action.remoteEntry.size,
                )
                currentIndex = currentIndex.withRecord(record)
                stateStore.save(currentIndex)
            }

            is SyncAction.Conflict -> {
                val localSource = Buffer().also { it.write(readLocal(action.localEntry.id)) }
                val remoteSource = remoteStorage.read(action.remoteEntry.path)
                val details = ConflictDetails(action.path, action.localEntry, action.remoteEntry, action.baseRecord)
                val outcome = conflictResolver.resolveConflict(details, localSource, remoteSource)

                val remoteFresh = remoteStorage.read(action.remoteEntry.path)
                val remoteBytes = remoteFresh.readByteArray()
                val remoteHash = Sha256.digest(remoteBytes).toHex()
                val mainLocalEntry = writeLocal(outcome.mainPath, remoteBytes)

                val conflictBytes = outcome.conflictSource.readByteArray()
                val conflictHash = Sha256.digest(conflictBytes).toHex()
                val conflictLocalEntry = writeLocal(outcome.conflictPath, conflictBytes)

                val conflictRemotePath = RemotePath(outcome.conflictPath)
                val conflictRemoteEntry = remoteStorage.write(conflictRemotePath, Buffer().also { it.write(conflictBytes) })

                val mainRecord = FileSyncRecord(
                    relativePath = outcome.mainPath,
                    localId = mainLocalEntry.id,
                    baseHash = remoteHash,
                    localSize = mainLocalEntry.size,
                    localModifiedAt = mainLocalEntry.modifiedAt,
                    remotePath = action.remoteEntry.path,
                    remoteRevision = action.remoteEntry.revision,
                    remoteModifiedAt = action.remoteEntry.modifiedAt,
                    remoteSize = action.remoteEntry.size,
                )
                val conflictRecord = FileSyncRecord(
                    relativePath = outcome.conflictPath,
                    localId = conflictLocalEntry.id,
                    baseHash = conflictHash,
                    localSize = conflictLocalEntry.size,
                    localModifiedAt = conflictLocalEntry.modifiedAt,
                    remotePath = conflictRemoteEntry.path,
                    remoteRevision = conflictRemoteEntry.revision,
                    remoteModifiedAt = conflictRemoteEntry.modifiedAt,
                    remoteSize = conflictRemoteEntry.size,
                )
                currentIndex = currentIndex.withRecord(mainRecord).withRecord(conflictRecord)
                stateStore.save(currentIndex)
            }

            is SyncAction.DeleteRemote -> {
                remoteStorage.delete(action.remotePath)
                currentIndex = currentIndex.withoutRecord(action.path)
                stateStore.save(currentIndex)
            }

            is SyncAction.DeleteLocal -> {
                deleteLocal(action.localId)
                currentIndex = currentIndex.withoutRecord(action.path)
                stateStore.save(currentIndex)
            }

            is SyncAction.UpdateMetadata, is SyncAction.NoOp -> currentIndex = applySilently(action, currentIndex)
        }
        return currentIndex
    }

    private suspend fun readLocal(id: VaultFileId): ByteArray =
        localIo(SyncProblemKind.ReadFailed) { localVault.read(id).readByteArray() }

    private suspend fun writeLocal(path: String, bytes: ByteArray): LocalEntry =
        localIo(SyncProblemKind.WriteFailed) { localVault.write(path, Buffer().also { it.write(bytes) }) }

    private suspend fun deleteLocal(id: VaultFileId) =
        localIo(SyncProblemKind.WriteFailed) { localVault.delete(id) }

    private inline fun <T> localIo(kind: SyncProblemKind, block: () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LocalIoFailure(kind, e)
        }

    private class LocalIoFailure(val kind: SyncProblemKind, override val cause: Exception) : Exception(cause.message, cause)

    private fun problemOf(path: String, action: SyncItemAction, error: Throwable): SyncProblem {
        val (kind, origin) = when (error) {
            is LocalIoFailure -> error.kind to error.cause
            is CryptException -> SyncProblemKind.DecryptFailed to error
            else -> SyncProblemKind.Unknown to error
        }
        val detail = origin.message ?: origin::class.simpleName ?: "Unknown error"
        return SyncProblem(path, action, kind, detail)
    }

    private fun SyncAction.toItemAction(): SyncItemAction? = when (this) {
        is SyncAction.Upload -> SyncItemAction.Upload
        is SyncAction.Download -> SyncItemAction.Download
        is SyncAction.DeleteLocal -> SyncItemAction.DeleteLocal
        is SyncAction.DeleteRemote -> SyncItemAction.DeleteRemote
        is SyncAction.Conflict -> SyncItemAction.Conflict
        is SyncAction.UpdateMetadata, is SyncAction.NoOp -> null
    }

    private fun SyncAction.weight(): Long = when (this) {
        is SyncAction.Upload -> localEntry.size
        is SyncAction.Download -> remoteEntry.size
        is SyncAction.Conflict -> localEntry.size + remoteEntry.size
        is SyncAction.DeleteLocal, is SyncAction.DeleteRemote -> SyncProgress.DELETE_WEIGHT_BYTES
        is SyncAction.UpdateMetadata, is SyncAction.NoOp -> 0L
    }

    private suspend fun listAllRemote(dir: RemotePath, into: MutableList<RemoteEntry>, onFile: () -> Unit) {
        for (entry in remoteStorage.list(dir)) {
            if (entry.isDirectory) {
                listAllRemote(entry.path, into, onFile)
            } else {
                into.add(entry)
                onFile()
            }
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
