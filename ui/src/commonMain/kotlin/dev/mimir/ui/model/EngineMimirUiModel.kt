package dev.mimir.ui.model

import dev.mimir.core.SyncEngine
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/** Модель поверх голого [SyncEngine] для платформ без демона (Android, web до этапов 6 и 9): настройка недоступна. */
class EngineMimirUiModel(private val engine: SyncEngine) : MimirUiModel {
    override val status: StateFlow<SyncStatus> = engine.status
    override val progress: StateFlow<SyncProgress?> = engine.progress
    override val vaultPath: StateFlow<String?> = MutableStateFlow(null)
    override val isLocked: StateFlow<Boolean> = MutableStateFlow(true)
    override val lastSyncTime: StateFlow<Instant?> = MutableStateFlow(null)
    override val accountEmail: StateFlow<String?> = MutableStateFlow(null)
    override val conflicts: StateFlow<List<UiConflictEntry>> = MutableStateFlow(emptyList())

    override suspend fun triggerSync(): Boolean {
        engine.syncNow()
        return true
    }

    override suspend fun configureVault(path: String): Int = unsupported()
    override suspend fun inspectVault(): UiVaultInspection = unsupported()
    override suspend fun unlock(password: String, rememberOnDevice: Boolean) = unsupported()
    override suspend fun resolveConflict(originalPath: String, conflictPath: String, keepConflict: Boolean) = unsupported()
    override suspend fun startDeviceLogin(): UiDeviceCode = unsupported()
    override suspend fun pollDeviceLogin(deviceCode: String): UiDevicePoll = unsupported()
    override suspend fun loginMethods(): Set<UiLoginMethod> = emptySet()
    override suspend fun startBrowserLogin(): String = unsupported()
    override suspend fun pollBrowserLogin(): UiBrowserPoll = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("Not available on this platform yet")
}
