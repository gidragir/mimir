package dev.mimir.daemon

import dev.mimir.core.ChangeFeedStorage
import dev.mimir.core.Cipher
import dev.mimir.core.RcloneCipher
import dev.mimir.core.EncryptedRemoteStorage
import dev.mimir.core.SyncStatus
import dev.mimir.core.fs.FileSystemLocalVault
import dev.mimir.core.fs.NioWatchServiceVaultWatcher
import dev.mimir.core.sync.DefaultSyncEngine
import dev.mimir.drive.GoogleDriveStorage
import dev.mimir.drive.api.GoogleDeviceCodeResponse
import dev.mimir.drive.client.DriveAuthException
import dev.mimir.drive.auth.GoogleAuthClient
import dev.mimir.drive.auth.TokenStorage
import dev.mimir.drive.client.DriveHttpClient
import dev.mimir.drive.path.DrivePathResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Синхронизация невозможна: нет vault, входа в облако или пароля шифрования. */
class SyncNotConfiguredException(message: String) : IllegalStateException(message)

fun interface RemoteStorageFactory {
    /** `null` — облако не настроено (например, нет client id). */
    fun create(httpClient: HttpClient, tokenStorage: TokenStorage, config: DaemonConfig): ChangeFeedStorage?
}

private val googleDriveFactory = RemoteStorageFactory { httpClient, tokenStorage, config ->
    val clientId = config.googleClientId ?: return@RemoteStorageFactory null
    val authClient = GoogleAuthClient(httpClient, clientId, config.googleClientSecret)
    val driveClient = DriveHttpClient(httpClient, tokenStorage, authClient)
    GoogleDriveStorage(driveClient, DrivePathResolver(driveClient, config.rootFolder))
}

fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
}

/** Собирает граф зависимостей демона и держит его в актуальном состоянии (вход, разблокировка, смена vault). */
class DaemonRuntime(
    val config: DaemonConfig,
    private val httpClient: HttpClient = defaultHttpClient(),
    private val remoteStorageFactory: RemoteStorageFactory = googleDriveFactory,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : DaemonService {
    private val configDir = Paths.get(config.configDir ?: DaemonConfig.load(env = System.getenv()).configDir!!)
    private val buildMutex = Mutex()
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.NotConfigured)

    @Volatile
    private var cipher: Cipher? = null

    @Volatile
    private var scheduler: SyncScheduler? = null

    @Volatile
    private var watcher: NioWatchServiceVaultWatcher? = null
    private var statusJob: Job? = null

    val tokenStorage: TokenStorage = FileTokenStorage(configDir.resolve("tokens.json"))
    val authClient: GoogleAuthClient? = config.googleClientId?.let { GoogleAuthClient(httpClient, it, config.googleClientSecret) }

    override val status: StateFlow<SyncStatus> = _status.asStateFlow()
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
        rebuild()
    }

    override suspend fun unlock(password: String) {
        cipher = RcloneCipher(password)
        rebuild()
    }

    /** Вызывается после успешного входа в облако: токены уже сохранены. */
    suspend fun refresh() = rebuild()

    /** `false`, если синхронизация уже идёт. */
    override suspend fun triggerSync(): Boolean =
        (scheduler ?: throw SyncNotConfiguredException("Vault path, encryption password or cloud storage not configured"))
            .triggerSync()

    override suspend fun conflicts(): List<ConflictFileEntry> {
        val vault = conflictVault() ?: return emptyList()
        return ConflictFiles.find(vault.list().map { it.relativePath })
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
    }

    override suspend fun startDeviceLogin(): GoogleDeviceCodeResponse =
        (authClient ?: throw SyncNotConfiguredException("Google client id not configured")).requestDeviceCode()

    override suspend fun pollDeviceLogin(deviceCode: String): DevicePoll {
        val client = authClient ?: throw SyncNotConfiguredException("Google client id not configured")
        val tokens = try {
            client.pollDeviceToken(deviceCode)
        } catch (e: DriveAuthException) {
            val message = e.message.orEmpty()
            return if ("authorization_pending" in message || "slow_down" in message) DevicePoll.Pending else DevicePoll.Expired(message)
        }
        tokenStorage.save(tokens)
        val email = runCatching { client.fetchAccount(tokens.accessToken).email }.getOrNull()?.takeIf { it.isNotEmpty() }
        accountEmail = email
        writeFileAtomically(configDir.resolve("account.json"), Json.encodeToString(AccountFile(email)))
        rebuild()
        return DevicePoll.Authorized(email)
    }

    fun close() {
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

    private suspend fun rebuild() = buildMutex.withLock {
        teardown()
        val vaultPath = config.vaultPath ?: return@withLock
        val cipher = cipher ?: return@withLock
        if (tokenStorage.load() == null) return@withLock
        val remote = remoteStorageFactory.create(httpClient, tokenStorage, config) ?: return@withLock

        val rules = dev.mimir.core.rules.SyncRules()
        val watcher = NioWatchServiceVaultWatcher(Paths.get(vaultPath), rules)
        val vault = FileSystemLocalVault(Path(vaultPath), rules, watcher)
        val encrypted = EncryptedRemoteStorage(remote, cipher)
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
        statusJob = scope.launch { engine.status.collect { _status.value = it } }
        _status.value = engine.status.value
        scheduler.start()
    }

    private fun teardown() {
        scheduler?.stop()
        watcher?.stop()
        statusJob?.cancel()
        scheduler = null
        watcher = null
        statusJob = null
        _status.value = SyncStatus.NotConfigured
    }
}
