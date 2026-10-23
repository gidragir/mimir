package dev.mimir.daemon

import dev.mimir.core.StubSyncEngine
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.system.exitProcess

private const val EXIT_PORT_IN_USE = 2

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
    embeddedServer(CIO, port = config.port, host = config.bind) {
        mimirRoutes(StubSyncEngine())
    }.start(wait = true)
    return 0
}

fun main() {
    exitProcess(runDaemon(DaemonConfig.fromEnv(System.getenv())))
}
