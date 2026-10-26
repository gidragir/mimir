package dev.mimir.desktop

import dev.mimir.daemon.DaemonConfig
import dev.mimir.daemon.DaemonRuntime
import dev.mimir.daemon.DaemonService
import dev.mimir.daemon.mimirRoutes
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
import java.util.logging.Logger

/** То, с чем работает окно: сервис демона и способ его остановить. */
class DesktopBackend(val service: DaemonService, val mode: Mode, private val onClose: () -> Unit) : AutoCloseable {
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

    suspend fun create(config: DaemonConfig): DesktopBackend {
        if (isMimirDaemon(config)) {
            logger.info("Mimir daemon found on ${config.bind}:${config.port}; using its HTTP API")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val client = dev.mimir.client.HttpDaemonClient(baseUrl = probeUrl(config), token = config.token, scope = scope)
            val adapter = DesktopDaemonServiceAdapter(client)
            return DesktopBackend(adapter, DesktopBackend.Mode.RemoteDaemon) {
                scope.cancel()
                client.close()
            }
        }

        val serveApi = isPortFree(config)
        if (!serveApi) logger.warning("Port ${config.port} on ${config.bind} is used by another program; the local HTTP API is disabled")
        val runtime = DaemonRuntime(config)
        withContext(Dispatchers.IO) { runtime.initialize() }
        val server = if (serveApi) {
            embeddedServer(ServerCIO, port = config.port, host = config.bind) { mimirRoutes(runtime, config) }.start(wait = false)
        } else {
            null
        }
        val mode = if (serveApi) DesktopBackend.Mode.InProcess else DesktopBackend.Mode.InProcessWithoutApi
        return DesktopBackend(runtime, mode) {
            server?.stop(100, 1_000)
            runtime.close()
        }
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
