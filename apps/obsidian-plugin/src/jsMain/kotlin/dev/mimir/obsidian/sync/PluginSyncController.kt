package dev.mimir.obsidian.sync

import dev.mimir.core.ChangeFeedStorage
import dev.mimir.core.RemoteUnavailableException
import dev.mimir.core.SyncAbortedException
import dev.mimir.core.SyncEngine
import dev.mimir.core.SyncItemAction
import dev.mimir.core.SyncProblem
import dev.mimir.drive.client.DriveAuthException
import dev.mimir.obsidian.daemon.DaemonPresence
import dev.mimir.obsidian.daemon.DaemonProbe
import dev.mimir.obsidian.net.isNetworkError
import dev.mimir.obsidian.platform.VaultEvent
import dev.mimir.obsidian.state.DeviceSecretStore
import dev.mimir.obsidian.state.PluginSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

enum class Trigger {
    Startup,
    Resume,
    VaultChange,
    Timer,
    Manual,
}

sealed interface PluginState {
    data object NotConnected : PluginState
    data object YieldsToDaemon : PluginState
    data object PausedForDaemon : PluginState
    data class Syncing(val done: Int, val total: Int) : PluginState
    data class UpToDate(
        val at: Instant,
        val problems: List<SyncProblem>,
        val conflicts: List<String>,
    ) : PluginState
    data object Offline : PluginState
    data class NeedsSignIn(val message: String) : PluginState
    data class Error(val message: String) : PluginState
}

class PluginSyncController(
    private val scope: CoroutineScope,
    private val assembler: EngineSource,
    private val probe: DaemonProbe,
    private val secrets: DeviceSecretStore,
    private val settings: PluginSettingsStore,
    private val vaultEvents: Flow<VaultEvent>,
    private val clock: Clock = Clock.System,
) {
    private val _state = MutableStateFlow<PluginState>(PluginState.NotConnected)
    val state: StateFlow<PluginState> = _state.asStateFlow()

    private var isVisible: Boolean = true
    private var lastResumeAt: Instant = Instant.fromEpochMilliseconds(0)
    private var hasLocalChanges: Boolean = false
    private var lastUpToDate: PluginState.UpToDate? = null

    private val runMutex = Mutex()
    private var isSyncRunning = false
    private var rerunRequested = false

    private var activeSyncJob: Job? = null
    private var activeEngine: SyncEngine? = null
    private var timerJob: Job? = null
    private var vaultEventsJob: Job? = null
    private var vaultDebounceJob: Job? = null

    companion object {
        const val ABORTED_MESSAGE = "Drive is empty but the sync index is not — sync stopped, nothing was deleted"
    }

    fun start() {
        startVaultEventsListener()
        startTimer()
        trigger(Trigger.Startup)
    }

    fun stop() {
        vaultEventsJob?.cancel()
        vaultEventsJob = null
        vaultDebounceJob?.cancel()
        vaultDebounceJob = null
        timerJob?.cancel()
        timerJob = null
        cancelActiveSync()
    }

    fun resetOnDisconnect() {
        lastUpToDate = null
        hasLocalChanges = false
        cancelActiveSync()
        _state.value = PluginState.NotConnected
    }

    private fun cancelActiveSync() {
        val curJob = activeSyncJob
        val curEngine = activeEngine
        activeSyncJob = null
        activeEngine = null
        curJob?.cancel()
        scope.launch {
            try {
                curEngine?.stop()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {}
        }
    }

    fun onVisibilityChanged(visible: Boolean) {
        isVisible = visible
        if (!visible) {
            timerJob?.cancel()
            timerJob = null
            if (isSyncRunning) {
                val curJob = activeSyncJob
                val curEngine = activeEngine
                activeSyncJob = null
                activeEngine = null
                scope.launch {
                    try {
                        curEngine?.stop()
                    } catch (_: Throwable) {}
                }
                curJob?.cancel()
                if (_state.value is PluginState.Syncing) {
                    _state.value = lastUpToDate ?: PluginState.NotConnected
                }
            }
        } else {
            startTimer()
            trigger(Trigger.Resume)
        }
    }

    fun trigger(t: Trigger) {
        if (t == Trigger.Resume) {
            val now = clock.now()
            if (now - lastResumeAt < 30.seconds) {
                return
            }
            lastResumeAt = now
        }

        scope.launch {
            scheduleSync(t)
        }
    }

    private fun startVaultEventsListener() {
        vaultEventsJob?.cancel()
        vaultEventsJob = scope.launch {
            vaultEvents.collect {
                hasLocalChanges = true
                vaultDebounceJob?.cancel()
                vaultDebounceJob = scope.launch {
                    delay(5.seconds)
                    trigger(Trigger.VaultChange)
                }
            }
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                delay(5.minutes)
                if (isVisible) {
                    trigger(Trigger.Timer)
                }
            }
        }
    }

    private suspend fun scheduleSync(trigger: Trigger) {
        runMutex.withLock {
            if (isSyncRunning) {
                rerunRequested = true
                return
            }
            isSyncRunning = true
        }

        activeSyncJob = scope.launch {
            var currentTrigger = trigger
            try {
                while (isActive) {
                    rerunRequested = false
                    executeSync(currentTrigger)
                    val shouldRerun = runMutex.withLock {
                        if (rerunRequested && isActive) {
                            rerunRequested = false
                            true
                        } else {
                            isSyncRunning = false
                            false
                        }
                    }
                    if (!shouldRerun) break
                    currentTrigger = Trigger.Manual
                }
            } finally {
                runMutex.withLock {
                    isSyncRunning = false
                }
                activeSyncJob = null
                activeEngine = null
            }
        }
    }

    private suspend fun executeSync(trigger: Trigger) {
        val pair = assembler.buildEngine()
        if (pair == null) {
            _state.value = PluginState.NotConnected
            return
        }

        val (engine, feed) = pair
        activeEngine = engine

        val settingsData = settings.get()
        if (!settingsData.forceSyncWithDaemon) {
            val presence = try {
                probe.check()
            } catch (_: Throwable) {
                DaemonPresence.Absent
            }
            when (presence) {
                DaemonPresence.ServesThisVault -> {
                    _state.value = PluginState.YieldsToDaemon
                    return
                }
                DaemonPresence.Unauthorized -> {
                    _state.value = PluginState.PausedForDaemon
                    return
                }
                DaemonPresence.Absent, DaemonPresence.ServesOtherVault -> {}
            }
        }

        // Ruling 1: Remote changes check for background triggers
        if ((trigger == Trigger.Timer || trigger == Trigger.Resume) && !hasLocalChanges) {
            val changeToken = secrets.loadChangeToken()
            if (changeToken != null) {
                val remoteChanges = try {
                    feed.getChanges(changeToken).changes
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null // Ошибка ленты -> выполняем прогон
                }
                if (remoteChanges != null && remoteChanges.isEmpty()) {
                    val current = lastUpToDate
                    if (current != null) {
                        val updated = current.copy(at = clock.now())
                        lastUpToDate = updated
                        _state.value = updated
                    }
                    return
                }
            }
        }

        val progressJob = scope.launch {
            engine.progress.collect { p ->
                if (p != null) {
                    _state.value = PluginState.Syncing(done = p.completed.size, total = p.total)
                }
            }
        }

        try {
            hasLocalChanges = false
            engine.syncNow()

            try {
                secrets.saveChangeToken(feed.getLatestChangeToken())
            } catch (_: Throwable) {}

            val p = engine.progress.value
            val problems = p?.problems ?: emptyList()
            val conflicts = p?.completed?.filter { it.action == SyncItemAction.Conflict }?.map { it.path } ?: emptyList()
            val upToDate = PluginState.UpToDate(at = clock.now(), problems = problems, conflicts = conflicts)
            lastUpToDate = upToDate
            _state.value = upToDate
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteUnavailableException) {
            _state.value = PluginState.Offline
        } catch (e: DriveAuthException) {
            _state.value = PluginState.NeedsSignIn(e.message ?: "Authentication required")
        } catch (e: SyncAbortedException) {
            _state.value = PluginState.Error(ABORTED_MESSAGE)
        } catch (e: Throwable) {
            if (e.isNetworkError()) {
                _state.value = PluginState.Offline
            } else {
                _state.value = PluginState.Error(e.message ?: "Sync error")
            }
        } finally {
            progressJob.cancel()
        }
    }
}
