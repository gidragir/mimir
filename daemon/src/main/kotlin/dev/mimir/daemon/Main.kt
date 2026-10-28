package dev.mimir.daemon

import dev.mimir.core.LocalVault
import dev.mimir.core.fs.FileSystemLocalVault
import dev.mimir.core.fs.NioWatchServiceVaultWatcher
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
 * Composition root JVM: [MimirRuntime] с настройками из [config], входом по настроенным клиентам Google
 * (оба способа пишут в один `tokens.json` и делят один источник токенов) и блокирующим IO на `Dispatchers.IO`.
 */
fun createRuntime(
    config: DaemonConfig,
    httpClient: HttpClient = defaultHttpClient(),
    cloudFactory: CloudStorageFactory = googleDriveFactory,
    conflictScanInterval: Duration = 5.seconds,
    browserLoginTimeout: Duration = 5.minutes,
    localVaultFactory: (Path) -> LocalVault = { FileSystemLocalVault(it, ioDispatcher = Dispatchers.IO) },
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
        watcherFactory = nioVaultWatchers,
        passwordProvider = { settings.config.resolvePassword() },
        ioDispatcher = Dispatchers.IO,
        httpClient = httpClient,
        cloudFactory = cloudFactory,
        scope = scope,
        conflictScanInterval = conflictScanInterval,
        localVaultFactory = localVaultFactory,
    )
}

// Ktor CIO пробрасывает ошибку bind из фонового потока (со стектрейсом), поэтому порт проверяем до старта.
private fun isPortFree(config: DaemonConfig): Boolean =
    try {
        ServerSocket().use { it.bind(InetSocketAddress(config.bind, config.port)) }
        true
    } catch (_: BindException) {
        false
    }

fun runDaemon(config: DaemonConfig): Int {
    if (!isPortFree(config)) {
        System.err.println("Port ${config.port} on ${config.bind} is already in use")
        return EXIT_PORT_IN_USE
    }
    val runtime = createRuntime(config)
    try {
        runBlocking { runtime.initialize() }
        embeddedServer(CIO, port = config.port, host = config.bind) {
            mimirRoutes(runtime, config)
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
