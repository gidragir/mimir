package dev.mimir.ui.model

import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/** Модель с настраиваемым состоянием и обработчиками: для тестов и превью. */
class FakeMimirUiModel(
    status: SyncStatus = SyncStatus.NotConfigured,
    vaultPath: String? = null,
    isLocked: Boolean = true,
    lastSyncTime: Instant? = null,
    accountEmail: String? = null,
    conflicts: List<UiConflictEntry> = emptyList(),
    override val progress: MutableStateFlow<SyncProgress?> = MutableStateFlow(null),
    private val onTriggerSync: () -> Boolean = { true },
    private val onConfigureVault: (String) -> Unit = {},
    private val fileCount: Int = 0,
    private val inspection: UiVaultInspection = UiVaultInspection(0, 0, false),
    private val onUnlock: (String, Boolean) -> Unit = { _, _ -> },
    private val onResolveConflict: (String, String, Boolean) -> Unit = { _, _, _ -> },
    private val deviceCode: UiDeviceCode = UiDeviceCode("ABCD-EFGH", "https://www.google.com/device", 1800, "device-code"),
    private val loginMethods: Set<UiLoginMethod> = setOf(UiLoginMethod.DeviceCode),
    private val browserAuthUrl: String = "https://accounts.google.com/o/oauth2/v2/auth",
    private val loginPoll: () -> UiLoginPoll = { UiLoginPoll.Pending },
    connectionStatus: dev.mimir.client.DaemonConnectionStatus = dev.mimir.client.DaemonConnectionStatus.Connected,
    backgroundSync: UiBackgroundSync? = null,
    updateState: UpdateUiState = UpdateUiState.Hidden,
) : MimirUiModel {
    override val updateState: MutableStateFlow<UpdateUiState> = MutableStateFlow(updateState)

    /** Вызовы действий обновления по порядку: `check`, `download`, `apply`, `dismiss`. */
    val updateCalls = mutableListOf<String>()

    override suspend fun checkForUpdates() { updateCalls += "check" }
    override suspend fun downloadUpdate() { updateCalls += "download" }
    override suspend fun applyUpdate() { updateCalls += "apply" }
    override fun dismissUpdate() { updateCalls += "dismiss" }

    override val connectionStatus: StateFlow<dev.mimir.client.DaemonConnectionStatus> = MutableStateFlow(connectionStatus)
    override val backgroundSync: MutableStateFlow<UiBackgroundSync?> = MutableStateFlow(backgroundSync)
    override val status: StateFlow<SyncStatus> = MutableStateFlow(status)

    override val vaultPath: MutableStateFlow<String?> = MutableStateFlow(vaultPath)
    override val isLocked: MutableStateFlow<Boolean> = MutableStateFlow(isLocked)
    override val lastSyncTime: StateFlow<Instant?> = MutableStateFlow(lastSyncTime)
    override val accountEmail: StateFlow<String?> = MutableStateFlow(accountEmail)
    override val conflicts: MutableStateFlow<List<UiConflictEntry>> = MutableStateFlow(conflicts)

    override suspend fun triggerSync(): Boolean = onTriggerSync()

    override suspend fun configureVault(path: String): Int {
        onConfigureVault(path)
        vaultPath.value = path
        return fileCount
    }

    override suspend fun inspectVault(): UiVaultInspection = inspection

    override suspend fun unlock(password: String, rememberOnDevice: Boolean) {
        onUnlock(password, rememberOnDevice)
        isLocked.value = false
    }

    override suspend fun resolveConflict(originalPath: String, conflictPath: String, keepConflict: Boolean) {
        onResolveConflict(originalPath, conflictPath, keepConflict)
        conflicts.value = conflicts.value.filterNot { it.conflictPath == conflictPath }
    }

    /** Способы, которыми начинали вход, по порядку. */
    val startedLogins = mutableListOf<UiLoginMethod>()

    override suspend fun loginMethods(): Set<UiLoginMethod> = loginMethods

    override suspend fun startLogin(method: UiLoginMethod): UiLoginStart {
        startedLogins += method
        return when (method) {
            UiLoginMethod.SystemAccount -> UiLoginStart.Started
            UiLoginMethod.Browser -> UiLoginStart.OpenUrl(browserAuthUrl)
            UiLoginMethod.DeviceCode -> UiLoginStart.ShowDeviceCode(deviceCode)
        }
    }

    override suspend fun pollLogin(): UiLoginPoll = loginPoll()
}
