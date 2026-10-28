package dev.mimir.ui.model

import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import dev.mimir.client.DaemonConnectionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

data class UiConflictEntry(val originalPath: String, val conflictPath: String, val createdAt: Instant)

data class UiDeviceCode(val userCode: String, val verificationUrl: String, val expiresInSeconds: Int, val deviceCode: String = "")

/** Сколько файлов лежит в локальной папке и в облаке; [requiresStrategyConfirmation] — есть данные с обеих сторон. */
data class UiVaultInspection(val localFileCount: Int, val remoteFileCount: Int, val requiresStrategyConfirmation: Boolean)

/** Способы входа в Google; порядок объявления — приоритет на шаге входа. */
enum class UiLoginMethod { SystemAccount, Browser, DeviceCode }

/** Что показать после начала входа. */
sealed interface UiLoginStart {
    data class OpenUrl(val url: String) : UiLoginStart
    data class ShowDeviceCode(val code: UiDeviceCode) : UiLoginStart
    /** Вход идёт без ссылки и кода (системное окно выбора аккаунта); остаётся ждать итога. */
    data object Started : UiLoginStart
}

sealed interface UiLoginPoll {
    data object Pending : UiLoginPoll
    data class Authorized(val email: String?) : UiLoginPoll
    data class Failed(val reason: String) : UiLoginPoll
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

    /** Связь с демоном; для моделей без сетевого демона всегда [DaemonConnectionStatus.Connected]. */
    val connectionStatus: StateFlow<DaemonConnectionStatus> get() = AlwaysConnected

    /** `false`, если синхронизация уже идёт. */
    suspend fun triggerSync(): Boolean

    /** Возвращает число найденных локальных файлов; бросает [IllegalArgumentException], если каталога нет. */
    suspend fun configureVault(path: String): Int

    /** Сверка локальной папки и облака после ввода пароля. */
    suspend fun inspectVault(): UiVaultInspection

    /** Бросает [IllegalArgumentException] с понятным сообщением, если пароль не подходит. */
    suspend fun unlock(password: String, rememberOnDevice: Boolean = true)
    suspend fun resolveConflict(originalPath: String, conflictPath: String, keepConflict: Boolean)

    /** Какие способы входа в Google доступны в этой сборке. */
    suspend fun loginMethods(): Set<UiLoginMethod>

    /** Начинает вход выбранным способом; новая попытка заменяет предыдущую. */
    suspend fun startLogin(method: UiLoginMethod): UiLoginStart

    /** Состояние последней начатой попытки входа. */
    suspend fun pollLogin(): UiLoginPoll
}

private val AlwaysConnected: StateFlow<DaemonConnectionStatus> = MutableStateFlow(DaemonConnectionStatus.Connected)
