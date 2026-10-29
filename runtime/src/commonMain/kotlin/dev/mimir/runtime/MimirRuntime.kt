package dev.mimir.runtime

import dev.mimir.client.DaemonConnectionStatus
import dev.mimir.client.DaemonLoginMethod
import dev.mimir.client.DaemonLoginPoll
import dev.mimir.client.DaemonLoginStart
import dev.mimir.client.DaemonService
import dev.mimir.client.DaemonVaultInspectionDto
import dev.mimir.core.Cipher
import dev.mimir.core.LocalVault
import dev.mimir.core.RcloneCipher
import dev.mimir.core.SyncItemAction
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import dev.mimir.core.fs.FileSystemLocalVault
import dev.mimir.runtime.login.LoginProvider
import dev.mimir.runtime.login.LoginRegistry
import dev.mimir.runtime.login.notConfiguredMessage
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val logger = java.util.logging.Logger.getLogger(MimirRuntime::class.java.name)

/**
 * Фасад синхронизации для всех точек входа (демон, desktop, Android): хранит состояние (пароль, вход, vault,
 * режим) и пересобирает движок через [EngineAssembler]. Платформа приходит портами конструктора.
 */
class MimirRuntime(
    private val settings: RuntimeSettingsStore,
    private val logins: LoginRegistry,
    watcherFactory: VaultWatcherFactory,
    private val passwordProvider: suspend () -> String?,
    ioDispatcher: CoroutineDispatcher,
    private val httpClient: HttpClient,
    cloudFactory: CloudStorageFactory = googleDriveFactory,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    conflictScanInterval: Duration = 5.seconds,
    /** Vault для разовых операций (конфликты, осмотр, подсчёт файлов); тесты подменяют его, чтобы считать обращения к диску. */
    localVaultFactory: (Path) -> LocalVault = { FileSystemLocalVault(it, ioDispatcher = ioDispatcher) },
) : DaemonService {
    @Volatile
    private var config: RuntimeConfig = settings.load()
    private val dataDir get() = config.dataDir
    private val account = AccountStore(config.dataDir)
    private val assembler = EngineAssembler(httpClient, cloudFactory, watcherFactory, ioDispatcher, scope) { config }
    private val localVaults = localVaultFactory
    private val conflictTracker = ConflictTracker(scope, watcherFactory, localVaultFactory, conflictScanInterval) { config.vaultPath }

    private val buildMutex = Mutex()
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.NotConfigured)
    private val _progress = MutableStateFlow<SyncProgress?>(null)
    private val _pendingLocalChanges = MutableStateFlow(false)

    @Volatile
    private var cipher: Cipher? = null

    @Volatile
    private var assembled: AssembledEngine? = null

    /** Пока `true`, собранный планировщик не запускается: ждём подтверждения первого слияния. */
    @Volatile
    private var holdInitialSync = false

    /** `false` — приложение в фоне: планировщик и наблюдатели остановлены, прогоны только через [syncOnce]. */
    @Volatile
    private var interactive = true
    private var engineJobs: List<Job> = emptyList()

    private val loginMutex = Mutex()
    private var activeLogin: LoginProvider? = null
    private var loginCompleted = false

    override val status: StateFlow<SyncStatus> = _status.asStateFlow()
    override val progress: StateFlow<SyncProgress?> = _progress.asStateFlow()
    override val conflictsFlow: StateFlow<List<ConflictFileEntry>> get() = conflictTracker.conflicts
    override val connectionStatus: StateFlow<DaemonConnectionStatus> = MutableStateFlow(DaemonConnectionStatus.Connected)
    override val vaultPath: String? get() = config.vaultPath
    override val isLocked: Boolean get() = cipher == null
    val isReady: Boolean get() = assembled != null
    override val lastSyncTime: Instant? get() = assembled?.scheduler?.lastSyncTime
    val pendingLocalChanges: StateFlow<Boolean> = _pendingLocalChanges.asStateFlow()

    @Volatile
    override var accountEmail: String? = null
        private set

    suspend fun initialize() {
        accountEmail = account.email()
        passwordProvider()?.takeIf { it.isNotEmpty() }?.let { cipher = RcloneCipher.create(it) }
        conflictTracker.refresh()
        if (interactive) conflictTracker.watch()
        rebuildLenient()
    }

    /** `false` — планировщик и наблюдатели остановлены (Android в фоне). */
    suspend fun setInteractive(active: Boolean) = buildMutex.withLock {
        if (interactive == active) return@withLock
        interactive = active
        val scheduler = assembled?.scheduler
        if (active) {
            conflictTracker.watch()
            if (!holdInitialSync) scheduler?.start()
        } else {
            scheduler?.stop(cancelRunner = false)
            assembled?.watcher?.stop()
            conflictTracker.stop()
        }
    }

    /** Один прогон до конца; `false` — синхронизация не настроена или ждёт подтверждения первого слияния. */
    suspend fun syncOnce(): Boolean {
        val scheduler = assembled?.scheduler ?: return false
        if (holdInitialSync) return false
        return scheduler.runNow()
    }

    /** Пути, записанные/удалённые движком локально за последние 2 минуты (эхо для триггеров). */
    fun recentlyWrittenBySync(): Set<String> {
        val since = Clock.System.now() - ECHO_WINDOW
        return _progress.value?.completed.orEmpty()
            .filter { it.action in LOCAL_WRITES && it.at >= since }
            .mapTo(mutableSetOf()) { it.path }
    }

    override suspend fun configureVault(vaultPath: String): Int {
        val normalized = vaultPath.trim()
        require(normalized.isNotEmpty() && Files.isDirectory(Paths.get(normalized))) { "Not a directory: $vaultPath" }
        settings.saveVaultPath(normalized)
        config = config.copy(vaultPath = normalized)
        // Первая настройка: пользователь подтвердит стратегию слияния, и только потом пойдёт синхронизация.
        holdInitialSync = !Files.exists(dataDir.resolve(EngineAssembler.INDEX_FILE))
        conflictTracker.refresh()
        if (interactive) conflictTracker.watch()
        rebuildLenient()
        return localVaults(Path(normalized)).list().size
    }

    override suspend fun inspectVault(): DaemonVaultInspectionDto {
        check(!isLocked) { "Vault is locked" }
        val remote = assembled?.remote ?: throw notConfigured()
        val local = localVaults(Path(config.vaultPath ?: throw SyncNotConfiguredException("Vault path not configured")))
        val localCount = local.list().size
        val remoteCount = remote.listAll().size
        return DaemonVaultInspectionDto(localCount, remoteCount, localCount > 0 && remoteCount > 0)
    }

    /** [InvalidPasswordException], если пароль не подходит к хранилищу в облаке; runtime остаётся заблокированным. */
    override suspend fun unlock(password: String) {
        val previous = cipher
        cipher = RcloneCipher.create(password)
        try {
            rebuild()
        } catch (e: Exception) {
            // Опечатка при повторной разблокировке не должна останавливать уже работающую синхронизацию.
            cipher = previous
            rebuildLenient()
            throw e
        }
    }

    /** `false`, если синхронизация уже идёт. */
    override suspend fun triggerSync(): Boolean {
        val scheduler = assembled?.scheduler ?: throw notConfigured()
        if (holdInitialSync) {
            holdInitialSync = false
            if (interactive) scheduler.start() else scheduler.triggerSync()
            return true
        }
        return scheduler.triggerSync()
    }

    override suspend fun conflicts() = conflictTracker.refresh()

    override suspend fun resolveConflict(originalPath: String, conflictPath: String, keepConflict: Boolean) =
        conflictTracker.resolve(originalPath, conflictPath, keepConflict)

    override suspend fun loginMethods(): Set<DaemonLoginMethod> = logins.methods()

    override suspend fun startLogin(method: DaemonLoginMethod): DaemonLoginStart = loginMutex.withLock {
        val provider = logins[method] ?: throw SyncNotConfiguredException(method.notConfiguredMessage())
        // Попытка другим способом (например, вкладка браузера после «Отмена») не должна перезаписать новый вход.
        activeLogin?.takeIf { it !== provider }?.cancel()
        activeLogin = null
        val start = provider.start()
        activeLogin = provider
        loginCompleted = false
        start
    }

    /** Первый [DaemonLoginPoll.Authorized] попытки запоминает аккаунт и пересобирает движок — ровно один раз. */
    override suspend fun pollLogin(): DaemonLoginPoll = loginMutex.withLock {
        val provider = activeLogin ?: return@withLock DaemonLoginPoll.Failed("Вход не начат")
        val result = provider.poll()
        if (result !is DaemonLoginPoll.Authorized || loginCompleted) return@withLock result
        try {
            // Токены уже сохранены провайдером: запоминаем аккаунт и перезапускаем синхронизацию.
            account.save(result.email, provider.method)
            accountEmail = result.email
            rebuildLenient()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warning("Saving sign-in failed: ${e.javaClass.name}")
            return@withLock DaemonLoginPoll.Failed("Не удалось сохранить вход")
        }
        loginCompleted = true
        result
    }

    /** Выход: провайдер забывает учётные данные, `account.json` удаляется; vault и индекс не трогаются. */
    suspend fun signOut() = loginMutex.withLock {
        account.method()?.let { logins[it] }?.signOut()
        account.delete()
        accountEmail = null
        activeLogin = null
        buildMutex.withLock { teardown() }
    }

    fun close() {
        logins.close()
        teardown()
        conflictTracker.stop()
        scope.cancel()
        httpClient.close()
    }

    private fun notConfigured() = SyncNotConfiguredException("Vault path, encryption password or cloud storage not configured")

    // Неверный пароль не должен ронять вход в облако или старт: runtime просто остаётся заблокированным.
    private suspend fun rebuildLenient() {
        try {
            rebuild()
        } catch (e: InvalidPasswordException) {
            logger.warning("Encryption password rejected: ${e.message}")
        } catch (e: SyncNotConfiguredException) {
            logger.warning("Sync is not started: ${e.message}")
        }
    }

    private suspend fun rebuild() = buildMutex.withLock {
        teardown()
        val vaultPath = config.vaultPath ?: return@withLock
        val cipher = cipher ?: return@withLock
        val tokenSource = account.method()?.let { logins[it] }?.tokenSource ?: return@withLock
        val built = try {
            assembler.assemble(vaultPath, cipher, tokenSource) ?: return@withLock
        } catch (e: InvalidPasswordException) {
            this.cipher = null
            throw e
        }
        assembled = built
        engineJobs = listOf(
            scope.launch {
                built.engine.status.collect {
                    _status.value = it
                    if (it == SyncStatus.Idle) runCatching { conflictTracker.refresh() }
                }
            },
            // Новый движок начинает с null — не затираем им сводку последнего прогона.
            scope.launch { built.engine.progress.filterNotNull().collect { _progress.value = it } },
            scope.launch { built.scheduler.pendingLocalChanges.collect { _pendingLocalChanges.value = it } },
        )
        _status.value = built.engine.status.value
        if (interactive && !holdInitialSync) built.scheduler.start()
    }

    private fun teardown() {
        assembled?.let {
            it.scheduler.stop()
            it.watcher.stop()
        }
        engineJobs.forEach { it.cancel() }
        engineJobs = emptyList()
        // _progress намеренно сохраняется: сводка последнего прогона
        assembled = null
        _status.value = SyncStatus.NotConfigured
    }

    private companion object {
        val ECHO_WINDOW = 2.minutes
        val LOCAL_WRITES = setOf(SyncItemAction.Download, SyncItemAction.DeleteLocal, SyncItemAction.Conflict)
    }
}
