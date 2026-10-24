package dev.mimir.daemon

import dev.mimir.core.SyncStatus
import dev.mimir.drive.api.GoogleDeviceCodeResponse
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/** То, что HTTP API требует от демона; реализуется [DaemonRuntime]. */
interface DaemonService {
    val status: StateFlow<SyncStatus>
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

    /** [IllegalArgumentException], если [vaultPath] не существует или не каталог. Путь сохраняется в `config.json`. */
    suspend fun configureVault(vaultPath: String)

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
