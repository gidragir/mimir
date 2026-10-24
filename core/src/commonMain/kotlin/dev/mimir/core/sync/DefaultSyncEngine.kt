package dev.mimir.core.sync

import dev.mimir.core.LocalVault
import dev.mimir.core.RemoteEntry
import dev.mimir.core.RemotePath
import dev.mimir.core.RemoteStorage
import dev.mimir.core.Sha256
import dev.mimir.core.SyncEngine
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import dev.mimir.core.rules.SyncRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
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
                _status.value = SyncStatus.Error(e.message ?: "Unknown sync error")
                throw e
            }
        }
    }

    private suspend fun performSync() {
        var currentIndex = stateStore.load()
        val localFiles = localVault.list()
        val remoteFiles = listAllRemote(RemotePath(""))

        val hashCalculator: suspend (String, Boolean) -> String = { path, isLocal ->
            if (isLocal) {
                val entry = localVault.getEntry(path)
                    ?: throw NoSuchElementException("Local file not found for hash: $path")
                val bytes = localVault.read(entry.id).readByteArray()
                Sha256.digest(bytes).toHex()
            } else {
                val bytes = remoteStorage.read(RemotePath(path)).readByteArray()
                Sha256.digest(bytes).toHex()
            }
        }

        val plan = planBuilder.buildPlan(localFiles, remoteFiles, currentIndex, hashCalculator)

        for (action in plan.actions) {
            when (action) {
                is SyncAction.Upload -> {
                    val bytes = localVault.read(action.localEntry.id).readByteArray()
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
                    val localEntry = localVault.write(action.path, Buffer().also { it.write(bytes) })
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
                    val localSource = localVault.read(action.localEntry.id)
                    val remoteSource = remoteStorage.read(action.remoteEntry.path)
                    val details = ConflictDetails(action.path, action.localEntry, action.remoteEntry, action.baseRecord)
                    val outcome = conflictResolver.resolveConflict(details, localSource, remoteSource)

                    val remoteFresh = remoteStorage.read(action.remoteEntry.path)
                    val remoteBytes = remoteFresh.readByteArray()
                    val remoteHash = Sha256.digest(remoteBytes).toHex()
                    val mainLocalEntry = localVault.write(outcome.mainPath, Buffer().also { it.write(remoteBytes) })

                    val conflictBytes = outcome.conflictSource.readByteArray()
                    val conflictHash = Sha256.digest(conflictBytes).toHex()
                    val conflictLocalEntry = localVault.write(outcome.conflictPath, Buffer().also { it.write(conflictBytes) })

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
                    localVault.delete(action.localId)
                    currentIndex = currentIndex.withoutRecord(action.path)
                    stateStore.save(currentIndex)
                }

                is SyncAction.UpdateMetadata -> {
                    currentIndex = currentIndex.withRecord(action.record)
                    stateStore.save(currentIndex)
                }

                is SyncAction.NoOp -> {
                    // no-op
                }
            }
        }
    }

    private suspend fun listAllRemote(dir: RemotePath): List<RemoteEntry> {
        val entries = remoteStorage.list(dir)
        val files = mutableListOf<RemoteEntry>()
        for (entry in entries) {
            if (entry.isDirectory) {
                files.addAll(listAllRemote(entry.path))
            } else {
                files.add(entry)
            }
        }
        return files
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
