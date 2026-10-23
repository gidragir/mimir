package dev.mimir.daemon

import dev.mimir.core.BuildConfig

data class DaemonConfig(val bind: String, val port: Int, val vaultPath: String?, val token: String?) {
    companion object
}

fun DaemonConfig.Companion.fromEnv(env: Map<String, String>): DaemonConfig {
    val port = env["MIMIR_PORT"]?.let {
        it.toIntOrNull()?.takeIf { port -> port in 0..65535 }
            ?: throw IllegalArgumentException("MIMIR_PORT must be a port number, got '$it'")
    } ?: BuildConfig.API_DEFAULT_PORT
    return DaemonConfig(
        bind = env["MIMIR_BIND"] ?: "127.0.0.1",
        port = port,
        vaultPath = env["MIMIR_VAULT"],
        token = env["MIMIR_TOKEN"],
    )
}
