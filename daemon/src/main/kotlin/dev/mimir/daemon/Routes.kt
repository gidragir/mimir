package dev.mimir.daemon

import dev.mimir.core.BuildConfig
import dev.mimir.core.SyncStatus
import dev.mimir.core.label
import dev.mimir.drive.client.DriveHttpException
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
    val activeConflicts: Int,
    val accountEmail: String?,
)

@Serializable
private data class StatusOnly(val status: String)

@Serializable
private data class ErrorBody(val error: String)

@Serializable
private data class ConflictsResponse(val conflicts: List<ConflictItem>)

@Serializable
private data class ConflictItem(val originalPath: String, val conflictPath: String, val createdAt: String)

@Serializable
private data class ResolveRequest(val originalPath: String = "", val conflictPath: String = "", val resolution: String = "")

@Serializable
private data class ResolveResponse(val status: String, val path: String)

@Serializable
private data class AuthorizedResponse(val status: String, val email: String?)

@Serializable
private data class ExpiredResponse(val status: String, val error: String)

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
                        activeConflicts = service.conflicts().size,
                        accountEmail = service.accountEmail,
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

            get("/conflicts") {
                call.respond(
                    ConflictsResponse(service.conflicts().map { ConflictItem(it.originalPath, it.conflictPath, it.createdAt.toString()) }),
                )
            }

            post("/conflicts/resolve") {
                val request = try {
                    call.receive<ResolveRequest>()
                } catch (_: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorBody("Expected originalPath, conflictPath and resolution"))
                }
                val keepConflict = when (request.resolution) {
                    "keep_conflict" -> true
                    "keep_original" -> false
                    else -> return@post call.respond(HttpStatusCode.BadRequest, ErrorBody("resolution must be keep_original or keep_conflict"))
                }
                try {
                    service.resolveConflict(request.originalPath, request.conflictPath, keepConflict)
                    call.respond(ResolveResponse("resolved", request.originalPath))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorBody(e.message ?: "Invalid request"))
                } catch (e: NoSuchElementException) {
                    call.respond(HttpStatusCode.NotFound, ErrorBody(e.message ?: "Not found"))
                } catch (_: SyncNotConfiguredException) {
                    call.respond(HttpStatusCode.PreconditionFailed, ErrorBody("Vault path not configured"))
                }
            }

            post("/auth/device/start") {
                try {
                    call.respond(service.startDeviceLogin())
                } catch (e: SyncNotConfiguredException) {
                    call.respond(HttpStatusCode.PreconditionFailed, ErrorBody(e.message ?: "Not configured"))
                } catch (e: DriveHttpException) {
                    call.respond(HttpStatusCode.BadGateway, ErrorBody(e.message ?: "Google request failed"))
                }
            }

            get("/auth/device/poll") {
                val deviceCode = call.request.queryParameters["device_code"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorBody("device_code is required"))
                try {
                    when (val result = service.pollDeviceLogin(deviceCode)) {
                        DevicePoll.Pending -> call.respond(StatusOnly("pending"))
                        is DevicePoll.Authorized -> call.respond(AuthorizedResponse("authorized", result.email))
                        is DevicePoll.Expired -> call.respond(HttpStatusCode.BadRequest, ExpiredResponse("expired", "Code has expired"))
                    }
                } catch (e: SyncNotConfiguredException) {
                    call.respond(HttpStatusCode.PreconditionFailed, ErrorBody(e.message ?: "Not configured"))
                } catch (e: DriveHttpException) {
                    call.respond(HttpStatusCode.BadGateway, ErrorBody(e.message ?: "Google request failed"))
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
                try {
                    service.unlock(request.password)
                    call.respond(StatusOnly("unlocked"))
                } catch (e: InvalidPasswordException) {
                    call.respond(HttpStatusCode.Forbidden, ErrorBody(e.message ?: "Invalid encryption password"))
                } catch (e: SyncNotConfiguredException) {
                    call.respond(HttpStatusCode.PreconditionFailed, ErrorBody(e.message ?: "Not configured"))
                }
            }
        }
    }
}
