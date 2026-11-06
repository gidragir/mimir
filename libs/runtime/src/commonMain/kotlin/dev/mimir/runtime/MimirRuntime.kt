package dev.mimir.runtime

import dev.mimir.client.DaemonConnectionStatus
import dev.mimir.client.DaemonLoginMethod
import dev.mimir.client.DaemonLoginPoll
import dev.mimir.client.DaemonLoginStart
import dev.mimir.client.DaemonService
import dev.mimir.client.DaemonVaultInspectionDto
import dev.mimir.client.UpdateApplyDto
import dev.mimir.client.UpdateStatusDto
import dev.mimir.runtime.update.UpdateFacade
import dev.mimir.core.Cipher
import dev.mimir.core.LocalVault
import dev.mimir.core.RcloneCipher
import dev.mimir.core.RemoteUnavailableException
import dev.mimir.core.SyncItemAction
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import dev.mimir.core.fs.FileSystemLocalVault
import dev.mimir.core.update.UpdateManager
import dev.mimir.runtime.login.LoginProvider
import dev.mimir.runtime.login.LoginRegistry
import dev.mimir.runtime.login.notConfiguredMessage
import dev.mimir.runtime.update.DisabledUpdateManager
import dev.mimir.runtime.update.UpdateScheduler
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
    /** Менеджер обновлений платформы (со своим `UpdateApplier`); `null` — обновления отключены, фоновая проверка не стартует. */
    updateManager: UpdateManager? = null,
    /** Подмена расписания проверок (тесты); по умолчанию — [UpdateScheduler] с задержками из спеки §6.2. */
    updateSchedulerFactory: ((UpdateManager, () -> Boolean) -> UpdateScheduler)? = null,
    /** Начальный режим: на Android процесс может стартовать в фоне для WorkManager без вызова `onStart`/`onStop`. */
    initialInteractive: Boolean = true,
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
    private val _vaultPath = MutableStateFlow(config.vaultPath)

    @Volatile
    private var cipher: Cipher? = null

    @Volatile
    private var assembled: AssembledEngine? = null

    /** Пока `true`, собранный планировщик не запускается: ждём подтверждения первого слияния. */
    @Volatile
    private var holdInitialSync = false

    /** `false` — приложение в фоне: планировщик и наблюдатели остановлены, прогоны только через [syncOnce]. */
    @Volatile
    private var interactive = initialInteractive
    private var engineJobs: List<Job> = emptyList()

    private val loginMutex = Mutex()
    private var activeLogin: LoginProvider? = null
    private var loginCompleted = false

    val updateManager: UpdateManager = updateManager ?: DisabledUpdateManager

    /** Проверка обновлений не прерывает синхронизацию: тик пропускается, пока статус — Syncing. */
    private val updateScheduler: UpdateScheduler? = updateManager?.let { manager ->
        val isSyncing = { _status.value == SyncStatus.Syncing }
        updateSchedulerFactory?.invoke(manager, isSyncing) ?: UpdateScheduler(manager, scope, isSyncing)
    }

    override val status: StateFlow<SyncStatus> = _status.asStateFlow()
    override val progress: StateFlow<SyncProgress?> = _progress.asStateFlow()
    override val conflictsFlow: StateFlow<List<ConflictFileEntry>> get() = conflictTracker.conflicts
    override val connectionStatus: StateFlow<DaemonConnectionStatus> = MutableStateFlow(DaemonConnectionStatus.Connected)
    override val vaultPath: String? get() = config.vaultPath
    val vaultPathFlow: StateFlow<String?> = _vaultPath.asStateFlow()
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
        if (interactive) updateScheduler?.start()
    }

    /**
     * `false` — планировщик и наблюдатели остановлены (Android в фоне).
     * Возвращает `true`, если при переходе в фон был прерван активный прогон или остались локальные изменения.
     */
    suspend fun setInteractive(active: Boolean): Boolean = buildMutex.withLock {
        if (interactive == active) return@withLock false
        interactive = active
        val scheduler = assembled?.scheduler
        if (active) {
            conflictTracker.watch()
            if (!holdInitialSync) scheduler?.start()
            updateScheduler?.start()
            false
        } else {
            val wasSyncing = scheduler?.isRunning == true || _status.value == SyncStatus.Syncing
            updateScheduler?.stop()
            scheduler?.stop(cancelRunner = true)
            assembled?.watcher?.stop()
            conflictTracker.stop()
            wasSyncing || _pendingLocalChanges.value
        }
    }

    @Volatile
    private var quiescedScheduler: SyncScheduler? = null

    /**
     * Подготовка к установке обновления: останавливает проверки обновлений, планировщик и наблюдатели, ждёт конец
     * идущего прогона (индекс сбрасывается движком в конце прогона) и удерживает новые прогоны до [resumeAfterQuiesce].
     * `false` — прогон не закончился за [timeout]; рантайм уже возобновлён, ничего не изменилось.
     */
    suspend fun quiesceForUpdate(timeout: Duration = 60.seconds): Boolean {
        val engine = assembled
        updateScheduler?.stop()
        engine?.scheduler?.stop(cancelRunner = false)
        engine?.watcher?.stop()
        if (engine != null && !engine.scheduler.pause(timeout)) {
            resumeAfterQuiesce(null)
            return false
        }
        quiescedScheduler = engine?.scheduler
        return true
    }

    /** Отменяет [quiesceForUpdate]: рантайм снова синхронизируется и проверяет обновления. */
    suspend fun resumeAfterQuiesce() = resumeAfterQuiesce(quiescedScheduler.also { quiescedScheduler = null })

    private suspend fun resumeAfterQuiesce(paused: SyncScheduler?) = buildMutex.withLock {
        if (interactive) {
            if (!holdInitialSync) assembled?.scheduler?.start()
            updateScheduler?.start()
        }
        paused?.resume()
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
        _vaultPath.value = normalized
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

    private val updates by lazy { UpdateFacade(this.updateManager, scope, { _status.value == SyncStatus.Syncing }) }

    override suspend fun getUpdate(): UpdateStatusDto = updates.get()
    override suspend fun checkUpdates(): UpdateStatusDto = updates.check()
    override suspend fun downloadUpdate() = updates.download()
    override suspend fun applyUpdate(): UpdateApplyDto = updates.apply()

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
        updateScheduler?.stop()
        logins.close()
        teardown()
        conflictTracker.stop()
        scope.cancel()
        httpClient.close()
    }

    private fun notConfigured() = SyncNotConfiguredException("Vault path, encryption password or cloud storage not configured")

    // Неверный пароль или временное отсутствие сети не должны ронять вход в облако или старт.
    private suspend fun rebuildLenient() {
        try {
            rebuild()
        } catch (e: InvalidPasswordException) {
            logger.warning("Encryption password rejected: ${e.message}")
        } catch (e: SyncNotConfiguredException) {
            logger.warning("Sync is not started: ${e.message}")
        } catch (e: RemoteUnavailableException) {
            logger.warning("Remote unavailable during rebuild: ${e.message}")
            _status.value = SyncStatus.Offline
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
