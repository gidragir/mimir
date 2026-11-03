package dev.mimir.runtime

import dev.mimir.client.DaemonLoginMethod
import dev.mimir.client.DaemonLoginPoll
import dev.mimir.client.DaemonLoginStart
import dev.mimir.core.LocalVault
import dev.mimir.core.VaultChange
import dev.mimir.core.fs.FileSystemLocalVault
import dev.mimir.core.fs.NioWatchServiceVaultWatcher
import dev.mimir.core.fs.VaultWatcher
import dev.mimir.drive.auth.AccessTokenSource
import dev.mimir.drive.auth.GoogleAuthClient
import dev.mimir.drive.auth.GoogleAuthClients
import dev.mimir.drive.auth.GoogleClientCredentials
import dev.mimir.drive.auth.GoogleClientKind
import dev.mimir.drive.auth.OAuthTokens
import dev.mimir.drive.auth.RefreshingTokenSource
import dev.mimir.runtime.login.BrowserLoginProvider
import dev.mimir.runtime.login.DeviceCodeLoginProvider
import dev.mimir.runtime.login.LoginProvider
import dev.mimir.runtime.login.LoginRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Настройки в памяти: как у демона с `config.json`, но без файла. */
internal class MemorySettingsStore(
    private val dataDir: java.nio.file.Path,
    @Volatile var vaultPath: String? = null,
    private val syncIntervalSeconds: Long = 3600,
) : RuntimeSettingsStore {
    override fun load() = RuntimeConfig(dataDir, vaultPath, syncIntervalSeconds = syncIntervalSeconds)
    override fun saveVaultPath(path: String) {
        vaultPath = path
    }
}

internal val nioWatchers = VaultWatcherFactory { root, rules, debounce ->
    NioWatchServiceVaultWatcher(Paths.get(root.toString()), rules, debounce)
}

internal fun jsonHttpClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
}

/** Google, который на всё отвечает успехом: код устройства, токены, профиль. */
internal fun mockGoogle() = HttpClient(
    MockEngine { request ->
        val body = when {
            request.url.encodedPath.endsWith("/device/code") ->
                """{"device_code":"dc","user_code":"UC","verification_url":"https://www.google.com/device","expires_in":1800,"interval":5}"""
            request.url.encodedPath.endsWith("/token") ->
                """{"access_token":"at","refresh_token":"rt","expires_in":3600,"token_type":"Bearer"}"""
            request.url.encodedPath.endsWith("/userinfo") -> """{"email":"user@gmail.com","name":"User"}"""
            else -> "{}"
        }
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    },
) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
}

/** Вход «на диске»: токены, как после входа по коду устройства в прежних версиях (без `account.json`). */
internal suspend fun signIn(dataDir: java.nio.file.Path) {
    FileTokenStorage(dataDir.resolve("tokens.json")).save(OAuthTokens("a", "r", 4_000_000_000L))
}

/** Сборка [MimirRuntime] как в демоне: провайдеры по настроенным клиентам и общий источник токенов. */
internal fun testRuntime(
    dataDir: java.nio.file.Path,
    vaultPath: String? = null,
    password: String? = null,
    cloud: CloudStorageFactory = CloudStorageFactory { _, _, _ -> null },
    httpClient: HttpClient = jsonHttpClient(),
    desktopClient: Boolean = false,
    deviceClient: Boolean = true,
    settings: RuntimeSettingsStore = MemorySettingsStore(dataDir, vaultPath),
    watcherFactory: VaultWatcherFactory = nioWatchers,
    conflictScanInterval: Duration = 5.seconds,
    providers: List<LoginProvider>? = null,
    localVaultFactory: (Path) -> LocalVault = { FileSystemLocalVault(it, ioDispatcher = Dispatchers.IO) },
    updateManager: dev.mimir.core.update.UpdateManager? = null,
    updateSchedulerFactory: ((dev.mimir.core.update.UpdateManager, () -> Boolean) -> dev.mimir.runtime.update.UpdateScheduler)? = null,
): MimirRuntime {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val tokenStorage = FileTokenStorage(dataDir.resolve("tokens.json"))
    val desktop = GoogleAuthClient(httpClient, GoogleClientCredentials(GoogleClientKind.Desktop, "desktop-id", null)).takeIf { desktopClient }
    val device = GoogleAuthClient(httpClient, GoogleClientCredentials(GoogleClientKind.Device, "client-id", null)).takeIf { deviceClient }
    val tokens = RefreshingTokenSource(tokenStorage, GoogleAuthClients(desktop, device))
    val logins = LoginRegistry(
        providers ?: listOfNotNull(
            desktop?.let { BrowserLoginProvider(it, tokenStorage, scope, Dispatchers.IO, 5.minutes, tokens) },
            device?.let { DeviceCodeLoginProvider(it, tokenStorage, tokens) },
        ),
    )
    return MimirRuntime(
        settings = settings,
        logins = logins,
        watcherFactory = watcherFactory,
        passwordProvider = { password },
        ioDispatcher = Dispatchers.IO,
        httpClient = httpClient,
        cloudFactory = cloud,
        scope = scope,
        conflictScanInterval = conflictScanInterval,
        localVaultFactory = localVaultFactory,
        updateManager = updateManager,
        updateSchedulerFactory = updateSchedulerFactory,
    )
}

/** Наблюдатель без ФС: считает активные подписки и вызовы [stop]. */
internal class FakeWatcher : VaultWatcher {
    val active = AtomicInteger()
    val stops = AtomicInteger()

    override fun start(): Flow<VaultChange> = flow {
        active.incrementAndGet()
        try {
            awaitCancellation()
        } finally {
            active.decrementAndGet()
        }
    }

    override fun stop() {
        stops.incrementAndGet()
    }

    override fun markWrittenBySync(relativePath: String) = Unit
}

internal class FakeWatcherFactory : VaultWatcherFactory {
    val created = java.util.Collections.synchronizedList(mutableListOf<Pair<Path, FakeWatcher>>())

    override fun create(root: Path, rules: dev.mimir.core.rules.SyncRules, debounce: Duration): VaultWatcher =
        FakeWatcher().also { created += root to it }

    fun activeCount() = created.sumOf { it.second.active.get() }
}

/** Источник токенов без сети: «вошёл», пока не вызван выход. */
internal class FakeTokenSource(@Volatile var signedIn: Boolean = true) : AccessTokenSource {
    override suspend fun token() = "t"
    override suspend fun refresh() = "t"
    override suspend fun hasCredentials() = signedIn
}

internal class FakeLoginProvider(
    override val method: DaemonLoginMethod,
    override val tokenSource: FakeTokenSource = FakeTokenSource(),
) : LoginProvider {
    val signOuts = AtomicInteger()

    override suspend fun start(): DaemonLoginStart = DaemonLoginStart.Started
    override suspend fun poll(): DaemonLoginPoll = DaemonLoginPoll.Authorized("user@example.com")
    override suspend fun signOut() {
        signOuts.incrementAndGet()
        tokenSource.signedIn = false
    }
}
