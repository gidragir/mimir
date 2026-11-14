package dev.mimir.daemon

import dev.mimir.core.LocalVault
import dev.mimir.core.RcloneCipher
import dev.mimir.core.fs.FileSystemLocalVault
import dev.mimir.core.fs.NioWatchServiceVaultWatcher
import dev.mimir.core.update.TargetPlatform
import dev.mimir.core.update.UpdateApplier
import dev.mimir.core.update.UpdateManager
import dev.mimir.runtime.update.UpdateScheduler
import dev.mimir.runtime.update.createGitHubUpdateManager
import dev.mimir.drive.auth.RefreshingTokenSource
import dev.mimir.runtime.CloudStorageFactory
import dev.mimir.runtime.FileTokenStorage
import dev.mimir.runtime.MimirRuntime
import dev.mimir.runtime.VaultWatcherFactory
import dev.mimir.runtime.googleDriveFactory
import dev.mimir.runtime.login.BrowserLoginProvider
import dev.mimir.runtime.login.DeviceCodeLoginProvider
import dev.mimir.runtime.login.LoginRegistry
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Paths
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.engine.cio.CIO as ClientCIO

private const val EXIT_PORT_IN_USE = 2

fun defaultHttpClient(): HttpClient = HttpClient(ClientCIO) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
}

/** Наблюдатель vault на JVM (daemon, desktop): NIO WatchService. */
val nioVaultWatchers = VaultWatcherFactory { root, rules, debounce ->
    NioWatchServiceVaultWatcher(Paths.get(root.toString()), rules, debounce)
}

/**
 * Менеджер обновлений по GitHub Releases или `null`, если проверка выключена ([DaemonConfig.updateCheck]):
 * тогда runtime остаётся с `DisabledUpdateManager` и фоновая проверка не стартует.
 */
fun createDaemonUpdateManager(
    config: DaemonConfig,
    httpClient: HttpClient,
    targetPlatform: TargetPlatform,
    applier: UpdateApplier,
): UpdateManager? =
    if (config.updateCheck) createGitHubUpdateManager(httpClient, targetPlatform, applier) else null

/**
 * Composition root JVM: [MimirRuntime] с настройками из [config], входом по настроенным клиентам Google
 * (оба способа пишут в один `tokens.json` и делят один источник токенов) и блокирующим IO на `Dispatchers.IO`.
 *
 * Обновления: по умолчанию демон только сообщает о новой версии ([DockerLogNotifyApplier], цель `DOCKER_ANY`);
 * встроенный desktop передаёт [updateApplier] и [targetPlatform] своей платформы.
 */
fun createRuntime(
    config: DaemonConfig,
    httpClient: HttpClient = defaultHttpClient(),
    cloudFactory: CloudStorageFactory = googleDriveFactory,
    conflictScanInterval: Duration = 5.seconds,
    browserLoginTimeout: Duration = 5.minutes,
    localVaultFactory: (Path) -> LocalVault = { FileSystemLocalVault(it, ioDispatcher = Dispatchers.IO) },
    updateApplier: UpdateApplier = DockerLogNotifyApplier(),
    targetPlatform: TargetPlatform = TargetPlatform.DOCKER_ANY,
    updateManager: UpdateManager? = createDaemonUpdateManager(config, httpClient, targetPlatform, updateApplier),
    updateSchedulerFactory: ((UpdateManager, () -> Boolean) -> UpdateScheduler)? = null,
    watcherFactory: VaultWatcherFactory = nioVaultWatchers,
    cipherFactory: suspend (String) -> RcloneCipher = { RcloneCipher.create(it) },
): MimirRuntime {
    val settings = DaemonSettingsStore(config)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val tokenStorage = FileTokenStorage(settings.load().dataDir.resolve("tokens.json"))
    val clients = config.authClients(httpClient)
    val tokens = RefreshingTokenSource(tokenStorage, clients)
    val logins = LoginRegistry(
        listOfNotNull(
            clients.desktop?.let { BrowserLoginProvider(it, tokenStorage, scope, Dispatchers.IO, browserLoginTimeout, tokens) },
            clients.device?.let { DeviceCodeLoginProvider(it, tokenStorage, tokens) },
        ),
    )
    return MimirRuntime(
        settings = settings,
        logins = logins,
        watcherFactory = watcherFactory,
        passwordProvider = { settings.config.resolvePassword() },
        ioDispatcher = Dispatchers.IO,
        httpClient = httpClient,
        cloudFactory = cloudFactory,
        scope = scope,
        conflictScanInterval = conflictScanInterval,
        localVaultFactory = localVaultFactory,
        updateManager = updateManager,
        updateSchedulerFactory = updateSchedulerFactory,
        cipherFactory = cipherFactory,
    )
}

// Ktor CIO пробрасывает ошибку bind из фонового потока (со стектрейсом), поэтому порт проверяем до старта.
fun DaemonConfig.isPortFree(): Boolean =
    try {
        ServerSocket().use { it.bind(InetSocketAddress(bind, port)) }
        true
    } catch (_: BindException) {
        false
    }

fun runDaemon(config: DaemonConfig, updateApplier: UpdateApplier = DockerLogNotifyApplier()): Int {
    if (!config.isPortFree()) {
        System.err.println("Port ${config.port} on ${config.bind} is already in use")
        return EXIT_PORT_IN_USE
    }
    val runtime = createRuntime(config, updateApplier = updateApplier)
    try {
        runBlocking { runtime.initialize() }
        embeddedServer(CIO, port = config.port, host = config.bind) {
            mimirRoutes(runtime, config, runtime.updateManager)
        }.start(wait = true)
    } finally {
        runtime.close()
    }
    return 0
}

fun main() {
    val config = try {
        DaemonConfig.load()
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(1)
    }
    exitProcess(runDaemon(config))
}
