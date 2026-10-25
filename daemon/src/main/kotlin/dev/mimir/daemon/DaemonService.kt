package dev.mimir.daemon

import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import dev.mimir.drive.api.GoogleDeviceCodeResponse
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** То, что HTTP API требует от демона; реализуется [DaemonRuntime]. */
interface DaemonService {
    val status: StateFlow<SyncStatus>

    /** Прогресс текущего прогона; после завершения хранит сводку последнего (фаза Finished). */
    val progress: StateFlow<SyncProgress?>
    val vaultPath: String?
    val isLocked: Boolean
    val lastSyncTime: Instant?
    val accountEmail: String?

    /** `false`, если синхронизация уже идёт; [SyncNotConfiguredException], если синхронизировать пока нечего. */
    suspend fun triggerSync(): Boolean
    suspend fun unlock(password: String)

    suspend fun conflicts(): List<ConflictFileEntry>

    /** Снимок активных конфликтов без обхода диска; обновляется при синхронизации, смене vault и разрешении конфликтов. */
    val conflictsFlow: StateFlow<List<ConflictFileEntry>>

    /**
     * [IllegalArgumentException], если [vaultPath] не существует или не каталог. Путь сохраняется в `config.json`.
     * Возвращает число локальных файлов. После первой настройки синхронизация не стартует, пока не вызван [triggerSync].
     */
    suspend fun configureVault(vaultPath: String): Int

    /** [IllegalStateException], если демон заблокирован; [SyncNotConfiguredException], если облако ещё не готово. */
    suspend fun inspectVault(): VaultInspectionDto

    /** [IllegalArgumentException] — пара путей некорректна, [NoSuchElementException] — копии нет, [SyncNotConfiguredException] — нет vault. */
    suspend fun resolveConflict(originalPath: String, conflictPath: String, keepConflict: Boolean)

    /** [SyncNotConfiguredException], если не задан Google client id. */
    suspend fun startDeviceLogin(): GoogleDeviceCodeResponse
    suspend fun pollDeviceLogin(deviceCode: String): DevicePoll

    /** [SyncNotConfiguredException], если вход через браузер не настроен. Возвращает ссылку для браузера. */
    suspend fun startBrowserLogin(): String
    suspend fun pollBrowserLogin(): BrowserLoginPoll

    /** Способы входа, для которых настроены клиенты Google. */
    suspend fun loginMethods(): Set<LoginMethod>
}

@Serializable
data class VaultInspectionDto(val localFileCount: Int, val remoteFileCount: Int, val requiresStrategyConfirmation: Boolean)

enum class LoginMethod { Browser, DeviceCode }

sealed interface DevicePoll {
    data object Pending : DevicePoll
    data class Authorized(val email: String?) : DevicePoll
    data class Expired(val reason: String) : DevicePoll
}

sealed interface BrowserLoginPoll {
    data object Pending : BrowserLoginPoll
    data class Authorized(val email: String?) : BrowserLoginPoll
    data class Failed(val reason: String) : BrowserLoginPoll
}
