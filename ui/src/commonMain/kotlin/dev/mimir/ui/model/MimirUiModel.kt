package dev.mimir.ui.model

import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

data class UiConflictEntry(val originalPath: String, val conflictPath: String, val createdAt: Instant)

data class UiDeviceCode(val userCode: String, val verificationUrl: String, val expiresInSeconds: Int, val deviceCode: String = "")

sealed interface UiDevicePoll {
    data object Pending : UiDevicePoll
    data class Authorized(val email: String?) : UiDevicePoll
    data class Expired(val reason: String) : UiDevicePoll
}

/** Сколько файлов лежит в локальной папке и в облаке; [requiresStrategyConfirmation] — есть данные с обеих сторон. */
data class UiVaultInspection(val localFileCount: Int, val remoteFileCount: Int, val requiresStrategyConfirmation: Boolean)

enum class UiLoginMethod { Browser, DeviceCode }

sealed interface UiBrowserPoll {
    data object Pending : UiBrowserPoll
    data class Authorized(val email: String?) : UiBrowserPoll
    data class Failed(val reason: String) : UiBrowserPoll
}

/** Всё, что нужно интерфейсу от приложения; `ui` не знает, кто его реализует (демон в процессе, HTTP-клиент, заглушка). */
interface MimirUiModel {
    val status: StateFlow<SyncStatus>
    val progress: StateFlow<SyncProgress?>
    val vaultPath: StateFlow<String?>
    val isLocked: StateFlow<Boolean>
    val lastSyncTime: StateFlow<Instant?>
    val accountEmail: StateFlow<String?>
    val conflicts: StateFlow<List<UiConflictEntry>>

    /** `false`, если синхронизация уже идёт. */
    suspend fun triggerSync(): Boolean

    /** Возвращает число найденных локальных файлов; бросает [IllegalArgumentException], если каталога нет. */
    suspend fun configureVault(path: String): Int

    /** Сверка локальной папки и облака после ввода пароля. */
    suspend fun inspectVault(): UiVaultInspection

    /** Бросает [IllegalArgumentException] с понятным сообщением, если пароль не подходит. */
    suspend fun unlock(password: String, rememberOnDevice: Boolean = true)
    suspend fun resolveConflict(originalPath: String, conflictPath: String, keepConflict: Boolean)
    suspend fun startDeviceLogin(): UiDeviceCode
    suspend fun pollDeviceLogin(deviceCode: String): UiDevicePoll

    /** Какие способы входа в Google доступны в этой сборке. */
    suspend fun loginMethods(): Set<UiLoginMethod>

    /** Начинает вход через браузер и возвращает ссылку для открытия. */
    suspend fun startBrowserLogin(): String
    suspend fun pollBrowserLogin(): UiBrowserPoll
}
