package dev.mimir.desktop

import dev.mimir.daemon.DaemonConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DaemonServiceFactoryTest {
    private val configDir = Files.createTempDirectory("mimir-factory-cfg")
    private val closers = mutableListOf<() -> Unit>()

    @AfterTest
    fun cleanup() {
        closers.reversed().forEach { runCatching { it() } }
        configDir.toFile().deleteRecursively()
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun config(port: Int) = DaemonConfig(port = port, configDir = configDir.toString(), syncIntervalSeconds = 3600)

    @Test
    fun `runs the daemon in process and serves the API when the port is free`() = runBlocking<Unit> {
        val port = freePort()
        val backend = DaemonServiceFactory.create(config(port)).also { closers += it::close }

        assertEquals(DesktopBackend.Mode.InProcess, backend.mode)
        val health = HttpClient(CIO).use { it.get("http://127.0.0.1:$port/health").bodyAsText() }
        assertTrue("\"status\":\"ok\"" in health, health)
    }

    @Test
    fun `connects to an already running Mimir daemon over HTTP`() {
        val port = freePort()
        // Сервер стартует вне runBlocking: иначе Ktor делает его дочерним корутином теста, и тест не завершится.
        val external = embeddedServer(ServerCIO, port = port, host = "127.0.0.1") {
            routing { get("/health") { call.respondText("""{"status":"ok","version":"0.1.0"}""", io.ktor.http.ContentType.Application.Json) } }
        }.start(wait = false)
        closers += { external.stop(0, 0) }

        val backend = runBlocking { DaemonServiceFactory.create(config(port)) }.also { closers += it::close }
        assertEquals(DesktopBackend.Mode.RemoteDaemon, backend.mode)
        assertIs<DesktopDaemonServiceAdapter>(backend.service)
        assertIs<dev.mimir.client.HttpDaemonClient>(backend.service.client)
    }

    @Test
    fun `a foreign process on the port does not crash the app and the daemon still runs in process`() = runBlocking<Unit> {
        val foreign = ServerSocket(0)
        closers += { foreign.close() }

        val backend = DaemonServiceFactory.create(config(foreign.localPort)).also { closers += it::close }
        assertEquals(DesktopBackend.Mode.InProcessWithoutApi, backend.mode)
    }
}
