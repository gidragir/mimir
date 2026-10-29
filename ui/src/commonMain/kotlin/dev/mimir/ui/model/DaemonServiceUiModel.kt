package dev.mimir.ui.model

import dev.mimir.client.DaemonLoginPoll
import dev.mimir.client.DaemonLoginStart
import dev.mimir.client.DaemonLoginMethod
import dev.mimir.client.DaemonService
import dev.mimir.core.ConflictFileEntry
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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

    init {
        scope.launch { service.status.collect { refresh() } }
        scope.launch {
            while (true) {
                delay(refreshInterval)
                refresh()
            }
        }
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
