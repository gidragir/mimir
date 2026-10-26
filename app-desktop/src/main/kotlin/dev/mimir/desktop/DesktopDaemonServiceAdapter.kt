package dev.mimir.desktop

import dev.mimir.client.DaemonBrowserLoginPoll
import dev.mimir.client.DaemonDevicePoll
import dev.mimir.client.DaemonLoginMethod
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import dev.mimir.daemon.BrowserLoginPoll
import dev.mimir.daemon.DevicePoll
import dev.mimir.daemon.LoginMethod
import dev.mimir.daemon.VaultInspectionDto
import dev.mimir.drive.api.GoogleDeviceCodeResponse
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/**
 * Адаптер между переносимым KMP [dev.mimir.client.DaemonService] и desktop-специфичным [dev.mimir.daemon.DaemonService].
 */
class DesktopDaemonServiceAdapter(
    val client: dev.mimir.client.HttpDaemonClient,
) : dev.mimir.daemon.DaemonService {
    override val status: StateFlow<SyncStatus> = client.status
    override val progress: StateFlow<SyncProgress?> = client.progress
    override val vaultPath: String? get() = client.vaultPath
    override val isLocked: Boolean get() = client.isLocked
    override val lastSyncTime: Instant? get() = client.lastSyncTime
    override val accountEmail: String? get() = client.accountEmail

    override val conflictsFlow: StateFlow<List<dev.mimir.daemon.ConflictFileEntry>> =
        object : StateFlow<List<dev.mimir.daemon.ConflictFileEntry>> {
            override val replayCache: List<List<dev.mimir.daemon.ConflictFileEntry>>
                get() = listOf(value)
            override val value: List<dev.mimir.daemon.ConflictFileEntry>
                get() = client.conflictsFlow.value.map {
                    dev.mimir.daemon.ConflictFileEntry(it.originalPath, it.conflictPath, it.createdAt)
                }
            override suspend fun collect(collector: FlowCollector<List<dev.mimir.daemon.ConflictFileEntry>>): Nothing {
                client.conflictsFlow.collect { list ->
                    collector.emit(list.map {
                        dev.mimir.daemon.ConflictFileEntry(it.originalPath, it.conflictPath, it.createdAt)
                    })
                }
            }
        }

    override suspend fun triggerSync(): Boolean = client.triggerSync()
    override suspend fun unlock(password: String) = client.unlock(password)
    override suspend fun conflicts(): List<dev.mimir.daemon.ConflictFileEntry> =
        client.conflicts().map { dev.mimir.daemon.ConflictFileEntry(it.originalPath, it.conflictPath, it.createdAt) }

    override suspend fun configureVault(vaultPath: String): Int = client.configureVault(vaultPath)

    override suspend fun inspectVault(): VaultInspectionDto {
        val res = client.inspectVault()
        return VaultInspectionDto(res.localFileCount, res.remoteFileCount, res.requiresStrategyConfirmation)
    }

    override suspend fun resolveConflict(originalPath: String, conflictPath: String, keepConflict: Boolean) =
        client.resolveConflict(originalPath, conflictPath, keepConflict)

    override suspend fun startDeviceLogin(): GoogleDeviceCodeResponse {
        val res = client.startDeviceLogin()
        return GoogleDeviceCodeResponse(res.deviceCode, res.userCode, res.verificationUrl, res.expiresInSeconds, res.intervalSeconds)
    }

    override suspend fun pollDeviceLogin(deviceCode: String): DevicePoll =
        when (val res = client.pollDeviceLogin(deviceCode)) {
            is DaemonDevicePoll.Pending -> DevicePoll.Pending
            is DaemonDevicePoll.Authorized -> DevicePoll.Authorized(res.email)
            is DaemonDevicePoll.Expired -> DevicePoll.Expired(res.reason)
        }

    override suspend fun startBrowserLogin(): String = client.startBrowserLogin()

    override suspend fun pollBrowserLogin(): BrowserLoginPoll =
        when (val res = client.pollBrowserLogin()) {
            is DaemonBrowserLoginPoll.Pending -> BrowserLoginPoll.Pending
            is DaemonBrowserLoginPoll.Authorized -> BrowserLoginPoll.Authorized(res.email)
            is DaemonBrowserLoginPoll.Failed -> BrowserLoginPoll.Failed(res.reason)
        }

    override suspend fun loginMethods(): Set<LoginMethod> =
        client.loginMethods().mapNotNull {
            when (it) {
                DaemonLoginMethod.Browser -> LoginMethod.Browser
                DaemonLoginMethod.DeviceCode -> LoginMethod.DeviceCode
            }
        }.toSet()
}
