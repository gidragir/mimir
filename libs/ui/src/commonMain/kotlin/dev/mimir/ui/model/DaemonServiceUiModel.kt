package dev.mimir.ui.model

import dev.mimir.client.DaemonLoginPoll
import dev.mimir.client.DaemonLoginStart
import dev.mimir.client.DaemonLoginMethod
import dev.mimir.client.DaemonService
import dev.mimir.client.UpdateStatus
import dev.mimir.client.UpdateStatusDto
import dev.mimir.client.UpdateCommandException
import dev.mimir.client.UpdateErrorCode
import dev.mimir.core.ConflictFileEntry
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Превращает [DaemonService] в [MimirUiModel]. Свойства сервиса не реактивны, поэтому снимок обновляется
 * при смене статуса, после каждого действия и по таймеру [refreshInterval].
 *
 * @param rememberPassword вызывается после успешной разблокировки с «Запомнить на этом устройстве»; `null` — не запоминать.
 */
class DaemonServiceUiModel(
    private val service: DaemonService,
    scope: CoroutineScope,
    private val rememberPassword: ((String) -> Unit)? = null,
    refreshInterval: Duration = 500.milliseconds,
    override val backgroundSync: StateFlow<UiBackgroundSync?> = MutableStateFlow(null),
    /** Как часто спрашивать об обновлении, пока ничего не происходит (находку ищет планировщик демона). */
    private val updateIdleInterval: Duration = 30.seconds,
    /** Как часто, пока идут проверка, скачивание или установка. */
    private val updateActiveInterval: Duration = 1.seconds,
) : MimirUiModel {

    private val _vaultPath = MutableStateFlow(service.vaultPath)
    private val _isLocked = MutableStateFlow(service.isLocked)
    private val _lastSyncTime = MutableStateFlow(service.lastSyncTime)
    private val _accountEmail = MutableStateFlow(service.accountEmail)

    override val status: StateFlow<SyncStatus> = service.status
    override val progress: StateFlow<SyncProgress?> = service.progress
    override val vaultPath: StateFlow<String?> = _vaultPath
    override val isLocked: StateFlow<Boolean> = _isLocked
    override val lastSyncTime: StateFlow<Instant?> = _lastSyncTime
    override val accountEmail: StateFlow<String?> = _accountEmail
    override val connectionStatus: StateFlow<dev.mimir.client.DaemonConnectionStatus> get() = service.connectionStatus

    override val conflicts: StateFlow<List<UiConflictEntry>> =
        service.conflictsFlow.map { list -> list.map { it.toUi() } }
            .stateIn(scope, SharingStarted.Eagerly, service.conflictsFlow.value.map { it.toUi() })

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Hidden)
    override val updateState: StateFlow<UpdateUiState> = _updateState

    @kotlin.concurrent.Volatile
    private var lastUpdate: UpdateStatusDto? = null

    @kotlin.concurrent.Volatile
    private var dismissedKey: String? = null

    /** Ошибка нашей команды (а не демона): опрос не должен её стирать, пока состояние не сдвинулось. */
    @kotlin.concurrent.Volatile
    private var localError = false

    /** Будит цикл опроса после команды, чтобы он сразу перешёл на частый ритм. */
    private val updateKick = Channel<Unit>(Channel.CONFLATED)

    init {
        // Один неспешный цикл: частый, только пока идёт проверка/скачивание/установка, иначе редкий.
        scope.launch {
            while (true) {
                val reachable = refreshUpdate()
                // Частый ритм только пока демон отвечает: без связи не долбим его каждую секунду.
                val interval = if (reachable && _updateState.value.isInProgress) updateActiveInterval else updateIdleInterval
                withTimeoutOrNull(interval) { updateKick.receive() }
            }
        }
        scope.launch { service.status.collect { refresh() } }
        scope.launch {
            while (true) {
                delay(refreshInterval)
                refresh()
            }
        }
    }

    private val UpdateUiState.isInProgress: Boolean
        get() = this is UpdateUiState.Checking || this is UpdateUiState.Downloading || this is UpdateUiState.Installing

    /** `false`, если демон не ответил (тогда остаётся последнее известное состояние). */
    private suspend fun refreshUpdate(): Boolean {
        val dto = try {
            service.getUpdate()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        lastUpdate = dto
        val moved = dto.status in MOVING
        if (localError && !moved) return true
        localError = false
        _updateState.value = dto.toUi(dismissedKey)
        return true
    }

    private inline fun runUpdateCommand(step: UpdateRetry, block: () -> Unit): Boolean =
        try {
            block()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            localError = true
            // Причину даёт устойчивый код; всё, что не отказ демона по состоянию, — проблема связи.
            val code = (e as? UpdateCommandException)?.code ?: UpdateErrorCode.NETWORK
            _updateState.value = UpdateUiState.Error(UpdateTexts.errorText(step, code), retryFor(step, code))
            false
        }

    private fun versionOfCurrent(): String = when (val s = _updateState.value) {
        is UpdateUiState.Available -> s.version
        is UpdateUiState.ReadyToInstall -> s.version
        is UpdateUiState.Downloading -> s.version
        is UpdateUiState.Installing -> s.version
        else -> lastUpdate?.latestVersion.orEmpty()
    }

    override suspend fun checkForUpdates() {
        localError = false
        dismissedKey = null
        _updateState.value = UpdateUiState.Checking
        runUpdateCommand(UpdateRetry.Check) {
            val dto = service.checkUpdates()
            lastUpdate = dto
            _updateState.value = dto.toUi()
        }
        updateKick.trySend(Unit)
    }

    override suspend fun downloadUpdate() {
        localError = false
        val version = versionOfCurrent()
        if (runUpdateCommand(UpdateRetry.Download) { service.downloadUpdate() }) {
            _updateState.value = UpdateUiState.Downloading(version, 0f)
            refreshUpdate()
        }
        updateKick.trySend(Unit)
    }

    override suspend fun applyUpdate() {
        localError = false
        val version = versionOfCurrent()
        _updateState.value = UpdateUiState.Installing(version)
        if (runUpdateCommand(UpdateRetry.Install) { service.applyUpdate() }) refreshUpdate()
        updateKick.trySend(Unit)
    }

    override fun dismissUpdate() {
        val dto = lastUpdate
        if (localError) {
            // Ошибка нашей команды: убираем её, само предложение остаётся.
            localError = false
            if (dto != null) _updateState.value = dto.toUi(dismissedKey)
            return
        }
        if (dto == null) return
        dismissedKey = dto.dismissKey()
        _updateState.value = dto.toUi(dismissedKey)
    }

    private fun refresh() {
        _vaultPath.value = service.vaultPath
        _isLocked.value = service.isLocked
        _lastSyncTime.value = service.lastSyncTime
        _accountEmail.value = service.accountEmail
    }

    override suspend fun triggerSync(): Boolean = service.triggerSync().also { refresh() }

    override suspend fun configureVault(path: String): Int {
        val count = service.configureVault(path)
        refresh()
        return count
    }

    override suspend fun inspectVault(): UiVaultInspection =
        service.inspectVault().let { UiVaultInspection(it.localFileCount, it.remoteFileCount, it.requiresStrategyConfirmation) }

    override suspend fun unlock(password: String, rememberOnDevice: Boolean) {
        service.unlock(password)
        if (rememberOnDevice) rememberPassword?.invoke(password)
        refresh()
    }

    override suspend fun resolveConflict(originalPath: String, conflictPath: String, keepConflict: Boolean) {
        service.resolveConflict(originalPath, conflictPath, keepConflict)
        service.conflicts()
    }

    override suspend fun loginMethods(): Set<UiLoginMethod> = service.loginMethods().mapTo(mutableSetOf()) { it.toUi() }

    override suspend fun startLogin(method: UiLoginMethod): UiLoginStart =
        when (val start = service.startLogin(method.toDaemon())) {
            is DaemonLoginStart.OpenUrl -> UiLoginStart.OpenUrl(start.url)
            is DaemonLoginStart.ShowDeviceCode -> start.code.let {
                UiLoginStart.ShowDeviceCode(UiDeviceCode(it.userCode, it.verificationUrl, it.expiresInSeconds.toInt(), it.deviceCode))
            }
            DaemonLoginStart.Started -> UiLoginStart.Started
        }

    override suspend fun pollLogin(): UiLoginPoll =
        when (val poll = service.pollLogin()) {
            DaemonLoginPoll.Pending -> UiLoginPoll.Pending
            is DaemonLoginPoll.Authorized -> UiLoginPoll.Authorized(poll.email).also { refresh() }
            is DaemonLoginPoll.Failed -> UiLoginPoll.Failed(poll.reason)
        }

    private fun DaemonLoginMethod.toUi(): UiLoginMethod = when (this) {
        DaemonLoginMethod.SystemAccount -> UiLoginMethod.SystemAccount
        DaemonLoginMethod.Browser -> UiLoginMethod.Browser
        DaemonLoginMethod.DeviceCode -> UiLoginMethod.DeviceCode
    }

    private fun UiLoginMethod.toDaemon(): DaemonLoginMethod = when (this) {
        UiLoginMethod.SystemAccount -> DaemonLoginMethod.SystemAccount
        UiLoginMethod.Browser -> DaemonLoginMethod.Browser
        UiLoginMethod.DeviceCode -> DaemonLoginMethod.DeviceCode
    }

    private fun ConflictFileEntry.toUi() = UiConflictEntry(originalPath, conflictPath, createdAt)
}

private val MOVING = setOf(UpdateStatus.CHECKING, UpdateStatus.DOWNLOADING, UpdateStatus.READY_TO_INSTALL, UpdateStatus.INSTALLING)
