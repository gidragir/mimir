package dev.mimir.desktop

import dev.mimir.core.update.TargetPlatform
import dev.mimir.core.update.UpdateHttpClient
import dev.mimir.core.update.UpdateHttpResponse
import dev.mimir.daemon.DaemonConfig
import dev.mimir.desktop.update.DesktopNotifyApplier
import dev.mimir.desktop.update.HostLifecycle
import dev.mimir.desktop.update.WindowsMsiApplier
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
import java.net.InetAddress
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
        val backend = DaemonServiceFactory.create(
            config(port),
            platform = TargetPlatform.WINDOWS_X64,
            updateTempDir = configDir.resolve("updates"),
        ).also { closers += it::close }

        assertEquals(DesktopBackend.Mode.InProcess, backend.mode)
        assertIs<WindowsMsiApplier>(backend.updateApplier)
        val health = HttpClient(CIO).use { it.get("http://127.0.0.1:$port/health").bodyAsText() }
        assertTrue("\"status\":\"ok\"" in health, health)
    }

    @Test
    fun `embedded daemon selects the MSI applier on Windows x64 and the notify applier elsewhere`() {
        val tmp = Files.createTempDirectory("mimir-fac-updates")
        closers += { tmp.toFile().deleteRecursively() }
        val noopClient = object : UpdateHttpClient {
            override suspend fun get(url: String, headers: Map<String, String>) = UpdateHttpResponse(200, emptyMap(), "")
            override suspend fun downloadStream(url: String, headers: Map<String, String>, onChunk: suspend (ByteArray, Long, Long) -> Unit) = Result.success(Unit)
        }
        val noopHost = object : HostLifecycle {
            override suspend fun quiesce() = true
            override suspend fun resume() = Unit
            override suspend fun shutdown() = Unit
        }
        val expected = mapOf(
            TargetPlatform.WINDOWS_X64 to WindowsMsiApplier::class,
            TargetPlatform.WINDOWS_ARM64 to DesktopNotifyApplier::class,
            TargetPlatform.LINUX_X64 to DesktopNotifyApplier::class,
        )
        for ((platform, type) in expected) {
            val applier = DaemonServiceFactory.createUpdateApplier(platform, tmp, { noopClient }, noopHost)
            assertEquals(type, applier::class, "$platform")
        }
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
        assertIs<dev.mimir.client.HttpDaemonClient>(backend.service)
    }

    @Test
    fun `a foreign process on the port does not crash the app and the daemon still runs in process`() = runBlocking<Unit> {
        // Чужой процесс держит именно 127.0.0.1: сокет на 0.0.0.0 macOS (BSD) позволяет перекрыть более конкретным адресом.
        val foreign = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        closers += { foreign.close() }

        val backend = DaemonServiceFactory.create(
            config(foreign.localPort),
            platform = TargetPlatform.LINUX_X64,
            updateTempDir = configDir.resolve("updates"),
        ).also { closers += it::close }
        assertEquals(DesktopBackend.Mode.InProcessWithoutApi, backend.mode)
        assertIs<DesktopNotifyApplier>(backend.updateApplier)
    }
}
