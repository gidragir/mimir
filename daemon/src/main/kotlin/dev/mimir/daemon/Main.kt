package dev.mimir.daemon

import dev.mimir.core.StubSyncEngine
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.net.BindException
import kotlin.system.exitProcess

private const val EXIT_PORT_IN_USE = 2

fun runDaemon(config: DaemonConfig): Int =
    try {
        embeddedServer(CIO, port = config.port, host = config.bind) {
            mimirRoutes(StubSyncEngine())
        }.start(wait = true)
        0
    } catch (e: Exception) {
        if (generateSequence<Throwable>(e) { it.cause }.any { it is BindException }) {
            System.err.println("Port ${config.port} on ${config.bind} is already in use")
            EXIT_PORT_IN_USE
        } else {
            throw e
        }
    }

fun main() {
    exitProcess(runDaemon(DaemonConfig.fromEnv(System.getenv())))
}
