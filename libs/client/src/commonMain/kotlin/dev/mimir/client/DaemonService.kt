package dev.mimir.client

import dev.mimir.core.ConflictFileEntry
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

interface DaemonService {
    val status: StateFlow<SyncStatus>
    val progress: StateFlow<SyncProgress?>
    val conflictsFlow: StateFlow<List<ConflictFileEntry>>
    val connectionStatus: StateFlow<DaemonConnectionStatus>
    val vaultPath: String?
    val isLocked: Boolean
    val lastSyncTime: Instant?
    val accountEmail: String?

    suspend fun triggerSync(): Boolean
    suspend fun conflicts(): List<ConflictFileEntry>
    suspend fun resolveConflict(originalPath: String, conflictPath: String, keepConflict: Boolean)
    suspend fun configureVault(vaultPath: String): Int
    suspend fun inspectVault(): DaemonVaultInspectionDto
    suspend fun unlock(password: String)
    suspend fun loginMethods(): Set<DaemonLoginMethod>

    /** [SyncNotConfiguredException], если способ недоступен. */
    suspend fun startLogin(method: DaemonLoginMethod): DaemonLoginStart

    /** Состояние последней начатой попытки входа; [DaemonLoginPoll.Failed], если вход не начат. */
    suspend fun pollLogin(): DaemonLoginPoll

    /** Снимок состояния обновления; `DISABLED`, если проверка выключена. */
    suspend fun getUpdate(): UpdateStatusDto

    /** Принудительная проверка; бросает [IllegalStateException] с понятным текстом, если GitHub недоступен или проверка выключена. */
    suspend fun checkUpdates(): UpdateStatusDto

    /** Запускает скачивание в фоне и сразу возвращается; прогресс читается через [getUpdate]. [IllegalStateException], если скачивать нечего. */
    suspend fun downloadUpdate()

    /** Запускает установку скачанного; [IllegalStateException], если рано или идёт синхронизация. */
    suspend fun applyUpdate(): UpdateApplyDto
}
