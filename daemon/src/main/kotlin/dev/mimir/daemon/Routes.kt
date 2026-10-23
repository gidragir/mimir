package dev.mimir.daemon

import dev.mimir.core.BuildConfig
import dev.mimir.core.SyncEngine
import dev.mimir.core.label
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
private data class Health(val status: String, val version: String)

@Serializable
private data class Status(val status: String)

// TODO: проверка токена (DaemonConfig.token) — спека 6.2; в скелете токен не проверяется.
fun Application.mimirRoutes(engine: SyncEngine) {
    install(ContentNegotiation) { json() }
    routing {
        get("/health") { call.respond(Health("ok", BuildConfig.VERSION)) }
        get("/api/status") { call.respond(Status(engine.status.value.label())) }
    }
}
