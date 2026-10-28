package dev.mimir.daemon

import dev.mimir.runtime.SyncNotConfiguredException

import dev.mimir.runtime.InvalidPasswordException

import dev.mimir.client.*
import dev.mimir.core.BuildConfig
import dev.mimir.core.SyncStatus
import dev.mimir.core.label
import dev.mimir.drive.client.DriveHttpException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
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

private typealias StatusResponse = DaemonStatusDto
private typealias StatusOnly = DaemonStatusOnlyResponse
private typealias ErrorBody = DaemonErrorBody
private typealias ConflictsResponse = DaemonConflictsResponse
private typealias ConflictItem = DaemonConflictItemDto
private typealias ResolveRequest = DaemonResolveConflictRequest
private typealias ResolveResponse = DaemonResolveConflictResponse
private typealias AuthorizedResponse = DaemonAuthorizedResponse
private typealias ExpiredResponse = DaemonExpiredResponse
private typealias MethodsResponse = DaemonMethodsResponse
private typealias AuthUrlResponse = DaemonAuthUrlResponse
private typealias FailedResponse = DaemonFailedResponse
private typealias VaultRequest = DaemonVaultRequest
private typealias VaultConfigResponse = DaemonVaultConfigResponse
private typealias UnlockRequest = DaemonUnlockRequest

private fun SyncStatus.code(): String = when (this) {
    SyncStatus.NotConfigured -> "not_connected"
    SyncStatus.Idle -> "idle"
    SyncStatus.Syncing -> "syncing"
    SyncStatus.Offline -> "offline"
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
    install(CORS) {
        // Только клиенты Mimir: расширение браузера и Obsidian. Произвольные сайты не должны управлять локальным API.
        allowOrigins { it.startsWith("chrome-extension://") || it == "app://obsidian.md" }
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader("X-Mimir-Token")
    }
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
                        activeConflicts = service.conflictsFlow.value.size,
                        accountEmail = service.accountEmail,
                        progress = service.progress.value?.toDto(kotlin.time.Clock.System.now()),
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

            // JSON маршрутов входа неизменен: им пользуются плагин и web-ui.
            post("/auth/device/start") {
                try {
                    val start = service.startLogin(LoginMethod.DeviceCode)
                    val code = (start as? DaemonLoginStart.ShowDeviceCode)?.code
                        ?: return@post call.respond(HttpStatusCode.InternalServerError, ErrorBody("Unexpected sign-in start: $start"))
                    call.respond(code)
                } catch (e: SyncNotConfiguredException) {
                    call.respond(HttpStatusCode.PreconditionFailed, ErrorBody(e.message ?: "Not configured"))
                } catch (e: DriveHttpException) {
                    call.respond(HttpStatusCode.BadGateway, ErrorBody(e.message ?: "Google request failed"))
                }
            }

            get("/auth/device/poll") {
                // Код устройства обязателен по контракту API; опрашивается попытка, начатая через /auth/device/start.
                call.request.queryParameters["device_code"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorBody("device_code is required"))
                try {
                    when (val result = service.pollLogin()) {
                        DaemonLoginPoll.Pending -> call.respond(StatusOnly("pending"))
                        is DaemonLoginPoll.Authorized -> call.respond(AuthorizedResponse("authorized", result.email))
                        is DaemonLoginPoll.Failed -> call.respond(HttpStatusCode.BadRequest, ExpiredResponse("expired", "Code has expired"))
                    }
                } catch (e: SyncNotConfiguredException) {
                    call.respond(HttpStatusCode.PreconditionFailed, ErrorBody(e.message ?: "Not configured"))
                } catch (e: DriveHttpException) {
                    call.respond(HttpStatusCode.BadGateway, ErrorBody(e.message ?: "Google request failed"))
                }
            }

            get("/auth/methods") {
                call.respond(MethodsResponse(service.loginMethods().sorted().map { it.wireName }))
            }

            post("/auth/browser/start") {
                try {
                    val start = service.startLogin(LoginMethod.Browser)
                    val url = (start as? DaemonLoginStart.OpenUrl)?.url
                        ?: return@post call.respond(HttpStatusCode.InternalServerError, ErrorBody("Unexpected sign-in start: $start"))
                    call.respond(AuthUrlResponse(url))
                } catch (_: SyncNotConfiguredException) {
                    call.respond(HttpStatusCode.Conflict, ErrorBody("Browser sign-in is not available"))
                }
            }

            get("/auth/browser/poll") {
                when (val result = service.pollLogin()) {
                    DaemonLoginPoll.Pending -> call.respond(StatusOnly("pending"))
                    is DaemonLoginPoll.Authorized -> call.respond(AuthorizedResponse("authorized", result.email))
                    is DaemonLoginPoll.Failed -> call.respond(FailedResponse("failed", result.reason))
                }
            }

            post("/vault") {
                val request = try {
                    call.receive<VaultRequest>()
                } catch (_: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorBody("Expected {\"vaultPath\": \"...\"}"))
                }
                try {
                    val fileCount = service.configureVault(request.vaultPath)
                    call.respond(VaultConfigResponse(service.vaultPath ?: request.vaultPath, fileCount))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorBody(e.message ?: "Invalid vault path"))
                }
            }

            get("/vault/inspect") {
                if (service.isLocked) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorBody("Vault is locked"))
                }
                try {
                    call.respond(service.inspectVault())
                } catch (e: SyncNotConfiguredException) {
                    call.respond(HttpStatusCode.PreconditionFailed, ErrorBody(e.message ?: "Not configured"))
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
