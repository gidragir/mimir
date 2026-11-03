package dev.mimir.desktop

import dev.mimir.daemon.DaemonConfig
import dev.mimir.daemon.createRuntime
import dev.mimir.daemon.DaemonService
import dev.mimir.daemon.mimirRoutes
import dev.mimir.core.update.TargetPlatform
import dev.mimir.core.update.UpdateApplier
import dev.mimir.desktop.update.DesktopApplierFactory
import dev.mimir.desktop.update.HostLifecycle
import dev.mimir.desktop.update.sweepStaleUpdateFiles
import dev.mimir.runtime.MimirRuntime
import dev.mimir.desktop.update.detectTargetPlatform
import dev.mimir.runtime.update.KtorUpdateHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

/** То, с чем работает окно: сервис демона и способ его остановить. */
class DesktopBackend(
    val service: DaemonService,
    val mode: Mode,
    /** Аппликер встроенного демона; `null` для удалённого (он решает сам). */
    val updateApplier: UpdateApplier? = null,
    private val onClose: () -> Unit,
) : AutoCloseable {
    enum class Mode {
        /** Демон внутри процесса вместе с HTTP API для плагина Obsidian. */
        InProcess,

        /** Порт занят чужим процессом: демон внутри процесса, но без HTTP API. */
        InProcessWithoutApi,

        /** Порт занят демоном Mimir: окно — клиент его HTTP API. */
        RemoteDaemon,
    }

    override fun close() = onClose()
}

object DaemonServiceFactory {
    private val logger = Logger.getLogger(DaemonServiceFactory::class.java.name)

    /**
     * Встроенный демон получает аппликер под ОС окна ([DesktopApplierFactory]); удалённый демон сам решает, как обновляться.
     * Параметры вынесены для тестов; по умолчанию — реальная ОС.
     */
    suspend fun create(
        config: DaemonConfig,
        platform: TargetPlatform = detectTargetPlatform(System.getProperty("os.name").orEmpty(), System.getProperty("os.arch").orEmpty()),
        updateTempDir: Path = Path.of(System.getProperty("java.io.tmpdir"), "mimir-updates"),
    ): DesktopBackend {
        if (isMimirDaemon(config)) {
            logger.info("Mimir daemon found on ${config.bind}:${config.port}; using its HTTP API")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val client = dev.mimir.client.HttpDaemonClient(baseUrl = probeUrl(config), token = config.token, scope = scope)
            return DesktopBackend(client, DesktopBackend.Mode.RemoteDaemon) {
                scope.cancel()
                client.close()
            }
        }

        val serveApi = isPortFree(config)
        if (!serveApi) logger.warning("Port ${config.port} on ${config.bind} is used by another program; the local HTTP API is disabled")
        val runtimeRef = AtomicReference<MimirRuntime?>()
        val closer = AtomicReference<() -> Unit>({})
        val updateHttp = HttpClient(CIO)
        val host = object : HostLifecycle {
            override suspend fun quiesce() = runtimeRef.get()?.quiesceForUpdate() ?: false
            override suspend fun resume() {
                runtimeRef.get()?.resumeAfterQuiesce()
            }

            // Не в корутине запроса: stop() сервера не должен ждать обработчик, который его и вызвал.
            override suspend fun shutdown() {
                Thread({ closer.get()() }, "mimir-update-shutdown").apply { isDaemon = true }.start()
            }
        }
        val applier = DesktopApplierFactory.create(
            platform = platform,
            httpClient = { KtorUpdateHttpClient(updateHttp) },
            tempDir = updateTempDir,
            host = host,
            browserOpener = SystemBrowserOpener()::openUri,
        )
        withContext(Dispatchers.IO) { runCatching { sweepStaleUpdateFiles(updateTempDir) } }
        val runtime = createRuntime(config, updateApplier = applier, targetPlatform = platform)
        runtimeRef.set(runtime)
        withContext(Dispatchers.IO) { runtime.initialize() }
        val server = if (serveApi) {
            embeddedServer(ServerCIO, port = config.port, host = config.bind) { mimirRoutes(runtime, config, runtime.updateManager) }.start(wait = false)
        } else {
            null
        }
        val mode = if (serveApi) DesktopBackend.Mode.InProcess else DesktopBackend.Mode.InProcessWithoutApi
        val closed = AtomicBoolean(false)
        val close = {
            if (closed.compareAndSet(false, true)) {
                server?.stop(100, 1_000)
                runtime.close()
                updateHttp.close()
            }
        }
        closer.set(close)
        return DesktopBackend(runtime, mode, updateApplier = applier, onClose = close)
    }

    private fun probeUrl(config: DaemonConfig): String {
        val host = if (config.bind == "0.0.0.0" || config.bind == "::") "127.0.0.1" else config.bind
        return "http://$host:${config.port}"
    }

    private suspend fun isMimirDaemon(config: DaemonConfig): Boolean = withContext(Dispatchers.IO) {
        HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 1_500; connectTimeoutMillis = 500 } }.use { client ->
            try {
                val body = Json.parseToJsonElement(client.get("${probeUrl(config)}/health").bodyAsText()).jsonObject
                body["status"]?.jsonPrimitive?.content == "ok" && body["version"] != null
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun isPortFree(config: DaemonConfig): Boolean =
        try {
            ServerSocket().use { it.bind(InetSocketAddress(config.bind, config.port)) }
            true
        } catch (_: BindException) {
            false
        }
}
