package dev.mimir.daemon

import dev.mimir.core.ChangeFeedStorage
import dev.mimir.core.Cipher
import dev.mimir.core.RcloneCipher
import dev.mimir.core.EncryptedRemoteStorage
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import dev.mimir.core.fs.FileSystemLocalVault
import dev.mimir.core.fs.NioWatchServiceVaultWatcher
import dev.mimir.core.sync.DefaultSyncEngine
import dev.mimir.drive.GoogleDriveStorage
import dev.mimir.drive.api.GoogleDeviceCodeResponse
import dev.mimir.drive.client.DriveAuthException
import dev.mimir.drive.client.DriveHttpException
import dev.mimir.drive.auth.GoogleAuthClient
import dev.mimir.drive.auth.GoogleAuthClients
import dev.mimir.drive.auth.LoginCallback
import dev.mimir.drive.auth.OAuthTokens
import dev.mimir.drive.auth.TokenStorage
import dev.mimir.drive.auth.newBrowserLogin
import dev.mimir.drive.client.DriveHttpClient
import dev.mimir.drive.path.DrivePathResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/** Синхронизация невозможна: нет vault, входа в облако или пароля шифрования. */
class SyncNotConfiguredException(message: String) : IllegalStateException(message)

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

    val tokenStorage: TokenStorage = FileTokenStorage(configDir.resolve("tokens.json"))
    val authClients: GoogleAuthClients = config.authClients(httpClient)

    override val status: StateFlow<SyncStatus> = _status.asStateFlow()
    override val progress: StateFlow<SyncProgress?> = _progress.asStateFlow()
    override val conflictsFlow: StateFlow<List<ConflictFileEntry>> = _conflicts.asStateFlow()
    override val vaultPath: String? get() = config.vaultPath
    override val isLocked: Boolean get() = cipher == null
    val isReady: Boolean get() = scheduler != null
    override val lastSyncTime: Instant? get() = scheduler?.lastSyncTime

    @Volatile
    override var accountEmail: String? = null
        private set

    suspend fun initialize() {
        accountEmail = readAccountEmail()
        config.resolvePassword()?.takeIf { it.isNotEmpty() }?.let { cipher = RcloneCipher(it) }
        refreshConflicts()
        startConflictScan()
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
        startConflictScan()
        rebuildLenient()
        return withContext(Dispatchers.IO) { FileSystemLocalVault(Path(normalized)).list().size }
    }

    override suspend fun inspectVault(): VaultInspectionDto {
        check(!isLocked) { "Vault is locked" }
        val remote = encryptedRemote ?: throw SyncNotConfiguredException("Vault path, encryption password or cloud storage not configured")
        val local = FileSystemLocalVault(Path(config.vaultPath ?: throw SyncNotConfiguredException("Vault path not configured")))
        val localCount = withContext(Dispatchers.IO) { local.list().size }
        val remoteCount = remote.listAll().size
        return VaultInspectionDto(localCount, remoteCount, localCount > 0 && remoteCount > 0)
    }

    /** [InvalidPasswordException], если пароль не подходит к хранилищу в облаке; демон остаётся заблокированным. */
    override suspend fun unlock(password: String) {
        val previous = cipher
        cipher = RcloneCipher(password)
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

    // Периодическое обновление снимка: конфликтные копии могут появиться и без нашей синхронизации (Obsidian, другие клиенты).
    private fun startConflictScan() {
        conflictJob?.cancel()
        conflictJob = scope.launch {
            while (true) {
                delay(conflictScanInterval)
                runCatching { refreshConflicts() }
            }
        }
    }

    private suspend fun refreshConflicts(): List<ConflictFileEntry> {
        val vault = conflictVault()
        val found = if (vault == null) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) { ConflictFiles.find(vault.list().map { it.relativePath }) }
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
            val bytes = vault.read(conflict.id).use { it.readByteArray() }
            vault.write(parsed.originalPath, Buffer().also { it.write(bytes) })
        }
        vault.deleteByPath(parsed.conflictPath)
        refreshConflicts()
    }

    override suspend fun startDeviceLogin(): GoogleDeviceCodeResponse =
        (authClients.device ?: throw SyncNotConfiguredException("Google device client is not configured")).requestDeviceCode()

    override suspend fun loginMethods(): Set<LoginMethod> = loginMethodsFor(config.googleCredentials())

    override suspend fun pollDeviceLogin(deviceCode: String): DevicePoll {
        val client = authClients.device ?: throw SyncNotConfiguredException("Google device client is not configured")
        val tokens = try {
            client.pollDeviceToken(deviceCode)
        } catch (e: DriveAuthException) {
            val message = e.message.orEmpty()
            return if ("authorization_pending" in message || "slow_down" in message) DevicePoll.Pending else DevicePoll.Expired(message)
        }
        return DevicePoll.Authorized(completeLogin(tokens, client))
    }

    private class BrowserAttempt(val receiver: BrowserLoginReceiver, val job: Job, val state: MutableStateFlow<BrowserLoginPoll>)

    private val browserMutex = Mutex()
    private var browserAttempt: BrowserAttempt? = null

    override suspend fun startBrowserLogin(): String = browserMutex.withLock {
        val client = authClients.desktop ?: throw SyncNotConfiguredException("Google desktop client is not configured")
        browserAttempt?.let {
            // Дожидаемся завершения старой попытки, чтобы она не сохранила вход параллельно с новой.
            // Задача попытки browserMutex не берёт, поэтому join под ним не взаимоблокируется.
            it.job.cancel()
            it.receiver.close()
            it.job.join()
        }
        browserAttempt = null
        val receiver = BrowserLoginReceiver(browserLoginTimeout)
        val request = try {
            receiver.start()
            client.newBrowserLogin(receiver.port).also { receiver.expectedState = it.state }
        } catch (e: Exception) {
            receiver.close()
            throw e
        }
        val state = MutableStateFlow<BrowserLoginPoll>(BrowserLoginPoll.Pending)
        val job = scope.launch {
            state.value = when (val callback = receiver.awaitCallback()) {
                null -> BrowserLoginPoll.Failed("Время ожидания истекло")
                LoginCallback.Denied -> BrowserLoginPoll.Failed("Вход отменён")
                is LoginCallback.Invalid -> BrowserLoginPoll.Failed("Вход не удался")
                is LoginCallback.Code -> {
                    val tokens = try {
                        client.exchangeAuthorizationCode(callback.code, request.verifier, request.redirectUri)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: DriveHttpException) {
                        logger.warning("Google rejected the authorization code: ${e.javaClass.simpleName}, HTTP ${e.statusCode}")
                        null.also { state.value = BrowserLoginPoll.Failed("Google не принял вход") }
                    } catch (e: Exception) {
                        logger.warning("Token exchange failed: ${e.javaClass.name}")
                        null.also { state.value = BrowserLoginPoll.Failed("Не удалось связаться с Google") }
                    }
                    if (tokens == null) return@launch
                    try {
                        BrowserLoginPoll.Authorized(completeLogin(tokens, client))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warning("Saving sign-in failed: ${e.javaClass.name}")
                        BrowserLoginPoll.Failed("Не удалось сохранить вход")
                    }
                }
            }
        }
        browserAttempt = BrowserAttempt(receiver, job, state)
        request.authUrl
    }

    override suspend fun pollBrowserLogin(): BrowserLoginPoll =
        browserMutex.withLock { browserAttempt?.state?.value } ?: BrowserLoginPoll.Failed("Вход не начат")

    /** Сохраняет токены, запоминает аккаунт и перезапускает синхронизацию. */
    private suspend fun completeLogin(tokens: OAuthTokens, client: GoogleAuthClient): String? {
        val email = try {
            client.fetchAccount(tokens.accessToken).email.takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        // Отменённая (перезапущенная) попытка входа не должна ничего сохранять.
        currentCoroutineContext().ensureActive()
        tokenStorage.save(tokens)
        accountEmail = email
        writeFileAtomically(configDir.resolve("account.json"), Json.encodeToString(AccountFile(email)))
        rebuildLenient()
        return email
    }

    fun close() {
        browserAttempt?.receiver?.close()
        scheduler?.stop()
        watcher?.stop()
        scope.cancel()
        httpClient.close()
    }

    @Serializable
    private class AccountFile(val email: String?)

    private fun readAccountEmail(): String? {
        val file = configDir.resolve("account.json")
        if (!Files.isRegularFile(file)) return null
        return runCatching { Json.decodeFromString<AccountFile>(Files.readString(file)).email }.getOrNull()
    }

    // Отдельный экземпляр без watcher'а: правки разрешения конфликта подхватит обычный watcher демона.
    private fun conflictVault(): FileSystemLocalVault? = config.vaultPath?.let { FileSystemLocalVault(Path(it)) }

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
        val vault = FileSystemLocalVault(Path(vaultPath), rules, watcher)
        val encrypted = EncryptedRemoteStorage(remote, cipher, reservedRootNames = setOf(CANARY_FILENAME))
        val engine = DefaultSyncEngine(vault, encrypted, JsonSyncStateStore(configDir.resolve("index.json")), rules = rules)
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
