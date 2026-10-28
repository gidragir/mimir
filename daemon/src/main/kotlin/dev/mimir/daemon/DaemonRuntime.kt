package dev.mimir.daemon

import dev.mimir.runtime.SyncNotConfiguredException

import dev.mimir.runtime.ConflictFileEntry

import dev.mimir.runtime.writeFileAtomically
import dev.mimir.runtime.ConflictFiles
import dev.mimir.runtime.SyncScheduler
import dev.mimir.runtime.JsonSyncStateStore
import dev.mimir.runtime.FileTokenStorage
import dev.mimir.runtime.verifyOrInitCanary
import dev.mimir.runtime.InvalidPasswordException
import dev.mimir.runtime.CANARY_FILENAME

import dev.mimir.client.DaemonLoginPoll
import dev.mimir.client.DaemonLoginStart
import dev.mimir.client.wireName
import dev.mimir.runtime.login.BrowserLoginProvider
import dev.mimir.runtime.login.DeviceCodeLoginProvider
import dev.mimir.runtime.login.LoginProvider
import dev.mimir.runtime.login.LoginRegistry
import dev.mimir.core.ChangeFeedStorage
import dev.mimir.core.Cipher
import dev.mimir.core.RcloneCipher
import dev.mimir.core.EncryptedRemoteStorage
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import dev.mimir.core.LocalVault
import dev.mimir.core.fs.FileSystemLocalVault
import dev.mimir.core.fs.NioWatchServiceVaultWatcher
import dev.mimir.core.sync.DefaultSyncEngine
import dev.mimir.drive.GoogleDriveStorage
import dev.mimir.drive.auth.GoogleAuthClients
import dev.mimir.drive.auth.RefreshingTokenSource
import dev.mimir.drive.auth.TokenStorage
import dev.mimir.drive.client.DriveHttpClient
import dev.mimir.drive.path.DrivePathResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.CancellationException
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
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val logger = java.util.logging.Logger.getLogger(DaemonRuntime::class.java.name)


fun interface RemoteStorageFactory {
    /** `null` — облако не настроено (например, нет client id). */
    fun create(httpClient: HttpClient, tokenStorage: TokenStorage, config: DaemonConfig): ChangeFeedStorage?
}

private val googleDriveFactory = RemoteStorageFactory { httpClient, tokenStorage, config ->
    val clients = config.authClients(httpClient)
    if (clients.desktop == null && clients.device == null) return@RemoteStorageFactory null
    val driveClient = DriveHttpClient(httpClient, tokenStorage, clients)
    GoogleDriveStorage(driveClient, DrivePathResolver(driveClient, config.rootFolder))
}

fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
}

/** Собирает граф зависимостей демона и держит его в актуальном состоянии (вход, разблокировка, смена vault). */
class DaemonRuntime(
    initialConfig: DaemonConfig,
    private val httpClient: HttpClient = defaultHttpClient(),
    private val remoteStorageFactory: RemoteStorageFactory = googleDriveFactory,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val conflictScanInterval: Duration = 5.seconds,
    private val browserLoginTimeout: Duration = 5.minutes,
    /** Vault для разовых операций (конфликты, осмотр, подсчёт файлов); тесты подменяют его, чтобы считать обращения к диску. */
    private val localVaultFactory: (Path) -> LocalVault = { FileSystemLocalVault(it, ioDispatcher = Dispatchers.IO) },
) : DaemonService {
    @Volatile
    var config: DaemonConfig = initialConfig
        private set

    private val configDir = Paths.get(config.configDir ?: DaemonConfig.load(env = System.getenv()).configDir!!)
    private val buildMutex = Mutex()
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.NotConfigured)
    private val _progress = MutableStateFlow<SyncProgress?>(null)
    private val _conflicts = MutableStateFlow<List<ConflictFileEntry>>(emptyList())

    @Volatile
    private var cipher: Cipher? = null

    @Volatile
    private var scheduler: SyncScheduler? = null

    @Volatile
    private var watcher: NioWatchServiceVaultWatcher? = null

    @Volatile
    private var encryptedRemote: EncryptedRemoteStorage? = null

    /** Пока `true`, собранный планировщик не запускается: ждём подтверждения первого слияния. */
    @Volatile
    private var holdInitialSync = false
    private var statusJob: Job? = null
    private var progressJob: Job? = null
    private var conflictJob: Job? = null
    private var vaultWatcher: NioWatchServiceVaultWatcher? = null

    val tokenStorage: TokenStorage = FileTokenStorage(configDir.resolve("tokens.json"))
    val authClients: GoogleAuthClients = config.authClients(httpClient)

    /** Способы входа по настроенным клиентам Google; оба пишут в одно хранилище токенов. */
    private val loginRegistry: LoginRegistry = RefreshingTokenSource(tokenStorage, authClients).let { tokens ->
        LoginRegistry(
            listOfNotNull(
                authClients.desktop?.let { BrowserLoginProvider(it, tokenStorage, scope, browserLoginTimeout, tokens) },
                authClients.device?.let { DeviceCodeLoginProvider(it, tokenStorage, tokens) },
            ),
        )
    }
    private val loginMutex = Mutex()
    private var activeLogin: LoginProvider? = null
    private var loginCompleted = false

    override val status: StateFlow<SyncStatus> = _status.asStateFlow()
    override val progress: StateFlow<SyncProgress?> = _progress.asStateFlow()
    override val conflictsFlow: StateFlow<List<ConflictFileEntry>> = _conflicts.asStateFlow()
    override val connectionStatus: StateFlow<dev.mimir.client.DaemonConnectionStatus> =
        MutableStateFlow(dev.mimir.client.DaemonConnectionStatus.Connected)
    override val vaultPath: String? get() = config.vaultPath
    override val isLocked: Boolean get() = cipher == null
    val isReady: Boolean get() = scheduler != null
    override val lastSyncTime: Instant? get() = scheduler?.lastSyncTime

    @Volatile
    override var accountEmail: String? = null
        private set

    suspend fun initialize() {
        accountEmail = readAccountEmail()
        config.resolvePassword()?.takeIf { it.isNotEmpty() }?.let { cipher = RcloneCipher.create(it) }
        refreshConflicts()
        startConflictWatcher()
        rebuildLenient()
    }

    override suspend fun configureVault(vaultPath: String): Int {
        val normalized = vaultPath.trim()
        require(normalized.isNotEmpty() && Files.isDirectory(Paths.get(normalized))) { "Not a directory: $vaultPath" }
        DaemonConfig.readFile(configDir.toString()).copy(vaultPath = normalized).save(configDir.toString())
        config = config.copy(vaultPath = normalized)
        // Первая настройка: пользователь подтвердит стратегию слияния, и только потом пойдёт синхронизация.
        holdInitialSync = !Files.exists(configDir.resolve("index.json"))
        refreshConflicts()
        startConflictWatcher()
        rebuildLenient()
        return localVaultFactory(Path(normalized)).list().size
    }

    override suspend fun inspectVault(): VaultInspectionDto {
        check(!isLocked) { "Vault is locked" }
        val remote = encryptedRemote ?: throw SyncNotConfiguredException("Vault path, encryption password or cloud storage not configured")
        val local = localVaultFactory(Path(config.vaultPath ?: throw SyncNotConfiguredException("Vault path not configured")))
        val localCount = local.list().size
        val remoteCount = remote.listAll().size
        return VaultInspectionDto(localCount, remoteCount, localCount > 0 && remoteCount > 0)
    }

    /** [InvalidPasswordException], если пароль не подходит к хранилищу в облаке; демон остаётся заблокированным. */
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

    /** Вызывается после успешного входа в облако: токены уже сохранены. */
    suspend fun refresh() = rebuildLenient()

    /** `false`, если синхронизация уже идёт. */
    override suspend fun triggerSync(): Boolean {
        val scheduler = scheduler ?: throw SyncNotConfiguredException("Vault path, encryption password or cloud storage not configured")
        if (holdInitialSync) {
            holdInitialSync = false
            scheduler.start()
            return true
        }
        return scheduler.triggerSync()
    }

    override suspend fun conflicts(): List<ConflictFileEntry> = refreshConflicts()

    private fun startConflictWatcher() {
        val vaultPath = config.vaultPath ?: return
        if (!Files.isDirectory(Paths.get(vaultPath))) return
        conflictJob?.cancel()
        vaultWatcher?.stop()
        val rules = dev.mimir.core.rules.SyncRules()
        val watcher = NioWatchServiceVaultWatcher(
            root = Paths.get(vaultPath),
            rules = rules,
            debounce = conflictScanInterval.coerceAtMost(1.seconds),
        )
        vaultWatcher = watcher
        // Только по событиям ФС: в простое vault не обходится. Разовый скан после регистрации ловит копии,
        // появившиеся до начала наблюдения. Сбой watcher'а (удалён корень, лимит inotify) не должен ронять демон;
        // конфликты обновит запрос.
        conflictJob = scope.launch {
            watcher.start(onWatching = { runCatching { refreshConflicts() } })
                .catch { }
                .filter { ConflictFiles.parse(it.id.value) != null }
                .conflate()
                .collect { runCatching { refreshConflicts() } }
        }
    }

    private suspend fun refreshConflicts(): List<ConflictFileEntry> {
        val vault = conflictVault()
        val found = if (vault == null) {
            emptyList()
        } else {
            ConflictFiles.find(vault.list().map { it.relativePath })
        }
        _conflicts.value = found
        return found
    }

    override suspend fun resolveConflict(originalPath: String, conflictPath: String, keepConflict: Boolean) {
        val vault = conflictVault() ?: throw SyncNotConfiguredException("Vault path not configured")
        val parsed = ConflictFiles.parse(conflictPath.replace('\\', '/').trim('/'))
        require(parsed != null && parsed.originalPath == originalPath.replace('\\', '/').trim('/')) {
            "'$conflictPath' is not a conflicted copy of '$originalPath'"
        }
        val conflict = vault.getEntry(parsed.conflictPath) ?: throw NoSuchElementException("No such conflict: $conflictPath")
        if (keepConflict) {
            vault.read(conflict.id).use { source ->
                vault.write(parsed.originalPath, source)
            }
        }
        vault.deleteByPath(parsed.conflictPath)
        refreshConflicts()
    }

    override suspend fun loginMethods(): Set<LoginMethod> = loginRegistry.methods()

    override suspend fun startLogin(method: LoginMethod): DaemonLoginStart = loginMutex.withLock {
        val provider = loginRegistry[method] ?: throw SyncNotConfiguredException(
            when (method) {
                LoginMethod.Browser -> "Google desktop client is not configured"
                LoginMethod.DeviceCode -> "Google device client is not configured"
                LoginMethod.SystemAccount -> "System account sign-in is not available"
            },
        )
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
            completeLogin(result.email, provider.method)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warning("Saving sign-in failed: ${e.javaClass.name}")
            return@withLock DaemonLoginPoll.Failed("Не удалось сохранить вход")
        }
        loginCompleted = true
        result
    }

    /** Токены уже сохранены провайдером: запоминает аккаунт и перезапускает синхронизацию. */
    private suspend fun completeLogin(email: String?, method: LoginMethod) {
        writeFileAtomically(configDir.resolve("account.json"), Json.encodeToString(AccountFile(email, method.wireName)))
        accountEmail = email
        rebuildLenient()
    }

    fun close() {
        loginRegistry.close()
        scheduler?.stop()
        watcher?.stop()
        vaultWatcher?.stop()
        conflictJob?.cancel()
        scope.cancel()
        httpClient.close()
    }

    @Serializable
    private class AccountFile(val email: String?, val method: String? = null)

    private fun readAccountEmail(): String? {
        val file = configDir.resolve("account.json")
        if (!Files.isRegularFile(file)) return null
        return runCatching { Json.decodeFromString<AccountFile>(Files.readString(file)).email }.getOrNull()
    }

    // Отдельный экземпляр без watcher'а: правки разрешения конфликта подхватит обычный watcher демона.
    // Блокирующий файловый IO (обход, чтение, запись, удаление) — на Dispatchers.IO, как у основного vault.
    private fun conflictVault(): LocalVault? = config.vaultPath?.let { localVaultFactory(Path(it)) }

    // Неверный пароль не должен ронять вход в облако или старт: демон просто остаётся заблокированным.
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
        if (tokenStorage.load() == null) return@withLock
        val remote = remoteStorageFactory.create(httpClient, tokenStorage, config) ?: return@withLock
        try {
            verifyOrInitCanary(remote, cipher, configDir)
        } catch (e: InvalidPasswordException) {
            this.cipher = null
            throw e
        }

        val rules = dev.mimir.core.rules.SyncRules()
        val watcher = NioWatchServiceVaultWatcher(Paths.get(vaultPath), rules)
        val vault = FileSystemLocalVault(Path(vaultPath), rules, watcher, ioDispatcher = Dispatchers.IO)
        val encrypted = EncryptedRemoteStorage(remote, cipher, reservedRootNames = setOf(CANARY_FILENAME))
        val engine = DefaultSyncEngine(
            vault,
            encrypted,
            JsonSyncStateStore(configDir.resolve("index.json")),
            rules = rules,
            parallelism = config.syncParallelism,
            dispatcher = Dispatchers.IO,
        )
        val scheduler = SyncScheduler(
            syncEngine = engine,
            syncInterval = config.syncIntervalSeconds.seconds,
            scope = scope,
            localChanges = vault.changes(),
            remoteFeed = encrypted,
        )
        this.watcher = watcher
        this.scheduler = scheduler
        this.encryptedRemote = encrypted
        statusJob = scope.launch {
            engine.status.collect {
                _status.value = it
                if (it == SyncStatus.Idle) runCatching { refreshConflicts() }
            }
        }
        progressJob = scope.launch {
            // Новый движок начинает с null — не затираем им сводку последнего прогона.
            engine.progress.filterNotNull().collect { _progress.value = it }
        }
        _status.value = engine.status.value
        if (!holdInitialSync) scheduler.start()
    }

    private fun teardown() {
        scheduler?.stop()
        watcher?.stop()
        statusJob?.cancel()
        progressJob?.cancel()
        progressJob = null
        // _progress намеренно сохраняется: сводка последнего прогона
        scheduler = null
        watcher = null
        encryptedRemote = null
        statusJob = null
        _status.value = SyncStatus.NotConfigured
    }
}
