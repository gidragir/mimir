package dev.mimir.daemon

import dev.mimir.core.BuildConfig
import dev.mimir.core.SyncStatus
import dev.mimir.core.label
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
private data class Health(val status: String, val version: String)

@Serializable
private data class StatusResponse(
    val status: String,
    val label: String,
    val vaultPath: String?,
    val lastSyncAt: String?,
    val isLocked: Boolean,
)

@Serializable
private data class StatusOnly(val status: String)

@Serializable
private data class ErrorBody(val error: String)

@Serializable
private data class UnlockRequest(val password: String = "")

private fun SyncStatus.code(): String = when (this) {
    SyncStatus.NotConfigured -> "not_connected"
    SyncStatus.Idle -> "idle"
    SyncStatus.Syncing -> "syncing"
    is SyncStatus.Error -> "error"
}

private fun tokensEqual(expected: String, actual: String?): Boolean =
    actual != null && MessageDigest.isEqual(expected.encodeToByteArray(), actual.encodeToByteArray())

private class TokenAuthConfig {
    var token: String = ""
}

private val MimirTokenAuth = createRouteScopedPlugin("MimirTokenAuth", ::TokenAuthConfig) {
    val expected = pluginConfig.token
    onCall { call ->
        val authorization = call.request.headers[HttpHeaders.Authorization]
        val bearer = authorization?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
        if (!tokensEqual(expected, bearer ?: call.request.headers["X-Mimir-Token"])) {
            call.respond(HttpStatusCode.Unauthorized, ErrorBody("Unauthorized"))
        }
    }
}

fun Application.mimirRoutes(service: DaemonService, config: DaemonConfig) {
    install(ContentNegotiation) { json() }
    routing {
        get("/health") { call.respond(Health("ok", BuildConfig.VERSION)) }
        staticResources("/", "static")

        route("/api/v1") {
            config.token?.let { install(MimirTokenAuth) { token = it } }

            get("/status") {
                val status = service.status.value
                call.respond(
                    StatusResponse(
                        status = status.code(),
                        label = status.label(),
                        vaultPath = service.vaultPath,
                        lastSyncAt = service.lastSyncTime?.toString(),
                        isLocked = service.isLocked,
                    ),
                )
            }

            post("/sync") {
                try {
                    if (service.triggerSync()) {
                        call.respond(HttpStatusCode.Accepted, StatusOnly("started"))
                    } else {
                        call.respond(HttpStatusCode.Conflict, StatusOnly("already_running"))
                    }
                } catch (_: SyncNotConfiguredException) {
                    call.respond(
                        HttpStatusCode.PreconditionFailed,
                        ErrorBody("Vault path, encryption password or cloud storage not configured"),
                    )
                }
            }

            post("/unlock") {
                val request = try {
                    call.receive<UnlockRequest>()
                } catch (_: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorBody("Expected {\"password\": \"...\"}"))
                }
                if (request.password.isEmpty()) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorBody("Password must not be empty"))
                }
                service.unlock(request.password)
                call.respond(StatusOnly("unlocked"))
            }
        }
    }
}
