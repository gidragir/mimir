package dev.mimir.daemon

import dev.mimir.client.DaemonConflictItemDto
import dev.mimir.client.DaemonConflictsResponse
import dev.mimir.client.DaemonErrorBody
import dev.mimir.client.DaemonResolveConflictRequest
import dev.mimir.client.DaemonResolveConflictResponse
import dev.mimir.client.DaemonStatusDto
import dev.mimir.client.DaemonStatusOnlyResponse
import dev.mimir.client.DaemonUnlockRequest
import dev.mimir.client.DaemonVaultConfigResponse
import dev.mimir.client.DaemonVaultRequest
import dev.mimir.client.InvalidPasswordException
import dev.mimir.client.SyncNotConfiguredException
import dev.mimir.core.BuildConfig
import dev.mimir.core.SyncStatus
import dev.mimir.core.label
import dev.mimir.core.update.UpdateManager
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
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
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

@Serializable
private data class Health(val status: String, val version: String)

private val sseJson = Json

private fun SyncStatus.code(): String = when (this) {
    SyncStatus.NotConfigured -> "not_connected"
    SyncStatus.Idle -> "idle"
    SyncStatus.Syncing -> "syncing"
    SyncStatus.Offline -> "offline"
    is SyncStatus.Error -> "error"
}

private fun DaemonService.toStatusDto(): DaemonStatusDto {
    val currentStatus = status.value
    return DaemonStatusDto(
        status = currentStatus.code(),
        label = currentStatus.label(),
        vaultPath = vaultPath,
        lastSyncAt = lastSyncTime?.toString(),
        isLocked = isLocked,
        activeConflicts = conflictsFlow.value.size,
        accountEmail = accountEmail,
        progress = progress.value?.toDto(Clock.System.now()),
    )
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
            call.respond(HttpStatusCode.Unauthorized, DaemonErrorBody("Unauthorized"))
        }
    }
}

/**
 * [updateManager] — менеджер обновлений (`MimirRuntime.updateManager`); `null` или `config.updateCheck == false` —
 * маршруты `/api/v1/update` отвечают статусом `DISABLED`.
 */
fun Application.mimirRoutes(service: DaemonService, config: DaemonConfig, updateManager: UpdateManager? = null) {
    install(CORS) {
        // Только клиенты Mimir: расширение браузера и Obsidian. Произвольные сайты не должны управлять локальным API.
        allowOrigins { isMimirClientOrigin(it) }
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

            updateRoutes(service, config, updateManager)
            authRoutes(service)

            get("/status") {
                call.respond(service.toStatusDto())
            }

            get("/events") {
                call.response.cacheControl(CacheControl.NoCache(null))
                call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                    suspend fun sendStatus() {
                        val payload = sseJson.encodeToString(DaemonStatusDto.serializer(), service.toStatusDto())
                        writeByteArray("event: status\ndata: $payload\n\n".encodeToByteArray())
                        flush()
                    }
                    val dirty = Channel<Unit>(Channel.CONFLATED)
                    // UNDISPATCHED регистрирует подписчиков StateFlow и сбрасывает их начальные значения до первого sendStatus(),
                    // чтобы изменения сразу после отправки стартового кадра не терялись.
                    val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                        merge(
                            service.status.drop(1).map { },
                            service.progress.drop(1).map { },
                            service.conflictsFlow.drop(1).map { },
                        ).collect { dirty.trySend(Unit) }
                    }
                    sendStatus()
                    try {
                        while (!isClosedForWrite) {
                            val changed = withTimeoutOrNull(15.seconds) {
                                dirty.receive()
                                true
                            } ?: false
                            if (isClosedForWrite) break
                            if (changed) {
                                sendStatus()
                            } else {
                                // Keep-alive комментарий обнаруживает закрытый сокет даже в простое.
                                writeByteArray(": ping\n\n".encodeToByteArray())
                                flush()
                            }
                        }
                    } finally {
                        collector.cancel()
                        dirty.close()
                    }
                }
            }

            post("/sync") {
                try {
                    if (service.triggerSync()) {
                        call.respond(HttpStatusCode.Accepted, DaemonStatusOnlyResponse("started"))
                    } else {
                        call.respond(HttpStatusCode.Conflict, DaemonStatusOnlyResponse("already_running"))
                    }
                } catch (_: SyncNotConfiguredException) {
                    call.respond(
                        HttpStatusCode.PreconditionFailed,
                        DaemonErrorBody("Vault path, encryption password or cloud storage not configured"),
                    )
                }
            }

            get("/conflicts") {
                call.respond(
                    DaemonConflictsResponse(
                        service.conflicts().map {
                            DaemonConflictItemDto(it.originalPath, it.conflictPath, it.createdAt.toString())
                        },
                    ),
                )
            }

            post("/conflicts/resolve") {
                val request = try {
                    call.receive<DaemonResolveConflictRequest>()
                } catch (_: Exception) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        DaemonErrorBody("Expected originalPath, conflictPath and resolution"),
                    )
                }
                val keepConflict = when (request.resolution) {
                    "keep_conflict" -> true
                    "keep_original" -> false
                    else -> return@post call.respond(
                        HttpStatusCode.BadRequest,
                        DaemonErrorBody("resolution must be keep_original or keep_conflict"),
                    )
                }
                try {
                    service.resolveConflict(request.originalPath, request.conflictPath, keepConflict)
                    call.respond(DaemonResolveConflictResponse("resolved", request.originalPath))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, DaemonErrorBody(e.message ?: "Invalid request"))
                } catch (e: NoSuchElementException) {
                    call.respond(HttpStatusCode.NotFound, DaemonErrorBody(e.message ?: "Not found"))
                } catch (_: SyncNotConfiguredException) {
                    call.respond(HttpStatusCode.PreconditionFailed, DaemonErrorBody("Vault path not configured"))
                }
            }

            post("/vault") {
                val request = try {
                    call.receive<DaemonVaultRequest>()
                } catch (_: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, DaemonErrorBody("Expected {\"vaultPath\": \"...\"}"))
                }
                try {
                    val fileCount = service.configureVault(request.vaultPath)
                    call.respond(DaemonVaultConfigResponse(service.vaultPath ?: request.vaultPath, fileCount))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, DaemonErrorBody(e.message ?: "Invalid vault path"))
                }
            }

            get("/vault/inspect") {
                if (service.isLocked) {
                    return@get call.respond(HttpStatusCode.Forbidden, DaemonErrorBody("Vault is locked"))
                }
                try {
                    call.respond(service.inspectVault())
                } catch (e: SyncNotConfiguredException) {
                    call.respond(HttpStatusCode.PreconditionFailed, DaemonErrorBody(e.message ?: "Not configured"))
                }
            }

            post("/unlock") {
                val request = try {
                    call.receive<DaemonUnlockRequest>()
                } catch (_: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, DaemonErrorBody("Expected {\"password\": \"...\"}"))
                }
                if (request.password.isEmpty()) {
                    return@post call.respond(HttpStatusCode.BadRequest, DaemonErrorBody("Password must not be empty"))
                }
                try {
                    service.unlock(request.password)
                    call.respond(DaemonStatusOnlyResponse("unlocked"))
                } catch (e: InvalidPasswordException) {
                    call.respond(HttpStatusCode.Forbidden, DaemonErrorBody(e.message ?: "Invalid encryption password"))
                } catch (e: SyncNotConfiguredException) {
                    call.respond(HttpStatusCode.PreconditionFailed, DaemonErrorBody(e.message ?: "Not configured"))
                }
            }
        }
    }
}
