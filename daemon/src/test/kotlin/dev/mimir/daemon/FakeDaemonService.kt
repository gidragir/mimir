package dev.mimir.daemon

import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import dev.mimir.drive.api.GoogleDeviceCodeResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

class FakeDaemonService : DaemonService {
    override val status = MutableStateFlow<SyncStatus>(SyncStatus.NotConfigured)
    override val progress = MutableStateFlow<SyncProgress?>(null)
    override var vaultPath: String? = null
    override var isLocked: Boolean = true
    override var lastSyncTime: Instant? = null
    override val accountEmail: String? = null

    override val conflictsFlow = MutableStateFlow<List<ConflictFileEntry>>(emptyList())
    override val connectionStatus = MutableStateFlow(dev.mimir.client.DaemonConnectionStatus.Connected)
    var rejectVault = false
    var fileCount = 0
    var inspection = VaultInspectionDto(0, 0, false)

    override suspend fun configureVault(vaultPath: String): Int {
        require(!rejectVault) { "Not a directory: $vaultPath" }
        this.vaultPath = vaultPath
        return fileCount
    }

    override suspend fun inspectVault(): VaultInspectionDto = inspection

    override suspend fun conflicts(): List<ConflictFileEntry> = emptyList()
    override suspend fun resolveConflict(originalPath: String, conflictPath: String, keepConflict: Boolean) = Unit
    override suspend fun startDeviceLogin(): dev.mimir.client.DaemonDeviceCodeDto = error("not used")
    override suspend fun pollDeviceLogin(deviceCode: String): DevicePoll = error("not used")
    /** `null` — вход через браузер недоступен. */
    var browserUrl: String? = "https://accounts.example/auth"
    var browserPoll: dev.mimir.client.DaemonBrowserLoginPoll = dev.mimir.client.DaemonBrowserLoginPoll.Pending
    override suspend fun startBrowserLogin(): String = browserUrl ?: throw SyncNotConfiguredException("no desktop client")
    override suspend fun pollBrowserLogin(): dev.mimir.client.DaemonBrowserLoginPoll = browserPoll
    var methods: Set<LoginMethod> = emptySet()
    override suspend fun loginMethods() = methods

    /** `true` — запуск принят, `false` — уже идёт, `null` — не сконфигурировано. */
    var syncAccepted: Boolean? = null
    var syncCalls = 0
    var unlockedWith: String? = null

    override suspend fun triggerSync(): Boolean {
        syncCalls++
        return syncAccepted ?: throw SyncNotConfiguredException("not configured")
    }

    override suspend fun unlock(password: String) {
        unlockedWith = password
        isLocked = false
    }
}
