package dev.mimir.desktop

import dev.mimir.core.SyncStatus
import dev.mimir.daemon.ConflictFileEntry
import dev.mimir.daemon.DaemonService
import dev.mimir.daemon.DevicePoll
import dev.mimir.ui.model.MimirUiModel
import dev.mimir.ui.model.UiConflictEntry
import dev.mimir.ui.model.UiDeviceCode
import dev.mimir.ui.model.UiBrowserPoll
import dev.mimir.ui.model.UiDevicePoll
import dev.mimir.ui.model.UiLoginMethod
import dev.mimir.daemon.BrowserLoginPoll
import dev.mimir.daemon.LoginMethod
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
) : MimirUiModel {
    private val _vaultPath = MutableStateFlow(service.vaultPath)
    private val _isLocked = MutableStateFlow(service.isLocked)
    private val _lastSyncTime = MutableStateFlow(service.lastSyncTime)
    private val _accountEmail = MutableStateFlow(service.accountEmail)

    override val status: StateFlow<SyncStatus> = service.status
    override val vaultPath: StateFlow<String?> = _vaultPath
    override val isLocked: StateFlow<Boolean> = _isLocked
    override val lastSyncTime: StateFlow<Instant?> = _lastSyncTime
    override val accountEmail: StateFlow<String?> = _accountEmail
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

    override suspend fun configureVault(path: String) {
        service.configureVault(path)
        refresh()
    }

    override suspend fun unlock(password: String, rememberOnDevice: Boolean) {
        service.unlock(password)
        if (rememberOnDevice) rememberPassword?.invoke(password)
        refresh()
    }

    override suspend fun resolveConflict(originalPath: String, conflictPath: String, keepConflict: Boolean) {
        service.resolveConflict(originalPath, conflictPath, keepConflict)
        service.conflicts()
    }

    override suspend fun startDeviceLogin(): UiDeviceCode {
        val response = service.startDeviceLogin()
        return UiDeviceCode(response.user_code, response.verification_url, response.expires_in.toInt(), response.device_code)
    }

    override suspend fun pollDeviceLogin(deviceCode: String): UiDevicePoll =
        when (val poll = service.pollDeviceLogin(deviceCode)) {
            DevicePoll.Pending -> UiDevicePoll.Pending
            is DevicePoll.Authorized -> UiDevicePoll.Authorized(poll.email).also { refresh() }
            is DevicePoll.Expired -> UiDevicePoll.Expired(poll.reason)
        }

    override suspend fun loginMethods(): Set<UiLoginMethod> = service.loginMethods().map {
        when (it) {
            LoginMethod.Browser -> UiLoginMethod.Browser
            LoginMethod.DeviceCode -> UiLoginMethod.DeviceCode
        }
    }.toSet()

    override suspend fun startBrowserLogin(): String = service.startBrowserLogin()

    override suspend fun pollBrowserLogin(): UiBrowserPoll =
        when (val poll = service.pollBrowserLogin()) {
            BrowserLoginPoll.Pending -> UiBrowserPoll.Pending
            is BrowserLoginPoll.Authorized -> UiBrowserPoll.Authorized(poll.email).also { refresh() }
            is BrowserLoginPoll.Failed -> UiBrowserPoll.Failed(poll.reason)
        }

    private fun ConflictFileEntry.toUi() = UiConflictEntry(originalPath, conflictPath, createdAt)
}
