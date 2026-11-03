package dev.mimir.daemon

import dev.mimir.client.DaemonErrorBody
import dev.mimir.client.DaemonService
import dev.mimir.client.DaemonStatusOnlyResponse
import dev.mimir.core.BuildConfig
import dev.mimir.core.SyncStatus
import dev.mimir.core.update.UpdateApplyResult
import dev.mimir.core.update.UpdateCheckResult
import dev.mimir.core.update.UpdateManager
import dev.mimir.core.update.UpdateState
import dev.mimir.runtime.update.DisabledUpdateManager
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.application
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import java.net.URI
import java.net.URISyntaxException

private val logger = java.util.logging.Logger.getLogger("dev.mimir.daemon.UpdateRoutes")

private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "[::1]")

/** Источники, которым разрешено управлять локальным API (расширение браузера и Obsidian); тот же список у CORS. */
internal fun isMimirClientOrigin(origin: String): Boolean =
    origin.startsWith("chrome-extension://") || origin == "app://obsidian.md"

/**
 * Команды обновления меняют систему (скачивание, установщик), поэтому браузерный запрос с чужого сайта отклоняется
 * независимо от токена: простой POST без заголовков проходит CORS без preflight. Запросы без `Origin` (curl, desktop,
 * демон-клиент) допускаются; из браузера — клиенты Mimir, страницы с loopback-хоста (встроенный web-ui)
 * и, если задан токен, страницы того же хоста, что и запрос (web-ui демона в Docker).
 */
private fun isTrustedCaller(call: ApplicationCall, config: DaemonConfig): Boolean {
    val origin = call.request.headers[HttpHeaders.Origin] ?: return true
    if (isMimirClientOrigin(origin)) return true
    val uri = try {
        URI(origin)
    } catch (_: URISyntaxException) {
        return false
    }
    if (uri.scheme != "http" && uri.scheme != "https") return false
    val host = uri.host ?: return false
    if (host in LOOPBACK_HOSTS) return true
    val requestHost = call.request.headers[HttpHeaders.Host]?.substringBefore(':')
    return config.token != null && host == requestHost
}

private fun UpdateState.effective(): UpdateState = if (this is UpdateState.Error) previousState else this

/**
 * `GET /update`, `POST /update/check|download|apply`. Скачивание долгое, поэтому `download` отвечает `202` и работает в
 * фоне, а клиент опрашивает `GET /update` (статус `DOWNLOADING` с `downloadProgress`, затем `READY_TO_INSTALL`
 * или `ERROR` с `errorMessage`) — как `/sync` + `/status`. `apply` быстрый (запуск установщика/уведомление) и
 * отвечает результатом сразу. Без менеджера или при выключенной проверке статус — `DISABLED`, команды дают `409`.
 */
internal fun Route.updateRoutes(service: DaemonService, config: DaemonConfig, manager: UpdateManager?) {
    val currentVersion = BuildConfig.VERSION
    val active = manager?.takeIf { config.updateCheck && it !== DisabledUpdateManager }

    suspend fun ApplicationCall.conflict(message: String) = respond(HttpStatusCode.Conflict, DaemonErrorBody(message))

    route("/update") {
        get {
            val status = active?.state?.value?.toDto(currentVersion) ?: disabledUpdateStatus(currentVersion)
            call.respond(status)
        }

        post("/check") {
            if (!isTrustedCaller(call, config)) return@post call.respond(HttpStatusCode.Forbidden, DaemonErrorBody("Forbidden origin"))
            if (active == null) return@post call.conflict("Update checks are disabled")
            val result = try {
                active.checkForUpdates(force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warning("Update check failed: ${e.javaClass.simpleName}")
                UpdateCheckResult.Error("Update check failed")
            }
            if (result is UpdateCheckResult.Error) {
                result.retryAfterSeconds?.let { call.response.header(HttpHeaders.RetryAfter, it.toString()) }
                return@post call.respond(HttpStatusCode.BadGateway, DaemonErrorBody(result.message))
            }
            call.respond(active.state.value.toDto(currentVersion))
        }

        post("/download") {
            if (!isTrustedCaller(call, config)) return@post call.respond(HttpStatusCode.Forbidden, DaemonErrorBody("Forbidden origin"))
            if (active == null) return@post call.conflict("Update checks are disabled")
            val state = active.state.value.effective()
            if (state !is UpdateState.Available) return@post call.conflict("No update is available to download")
            if (state.asset == null) return@post call.conflict("This update has no installable file for this platform")
            // UNDISPATCHED: менеджер успевает перейти в Downloading до ответа, и повторный запрос получит 409.
            call.application.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    active.downloadUpdate().exceptionOrNull()?.let {
                        logger.warning("Update download failed: ${it.javaClass.simpleName}")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warning("Update download failed: ${e.javaClass.simpleName}")
                }
            }
            call.respond(HttpStatusCode.Accepted, DaemonStatusOnlyResponse("started"))
        }

        post("/apply") {
            if (!isTrustedCaller(call, config)) return@post call.respond(HttpStatusCode.Forbidden, DaemonErrorBody("Forbidden origin"))
            if (active == null) return@post call.conflict("Update checks are disabled")
            if (active.state.value.effective() !is UpdateState.ReadyToInstall) {
                return@post call.conflict("No downloaded update to install")
            }
            if (service.status.value == SyncStatus.Syncing) {
                return@post call.conflict("A sync run is in progress; try again when it finishes")
            }
            val result = try {
                active.applyUpdate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warning("Update apply failed: ${e.javaClass.simpleName}")
                UpdateApplyResult.Failed("Update failed")
            }
            val body = result.toDto()
            if (body == null) {
                val failed = result as UpdateApplyResult.Failed
                failed.cause?.let { logger.warning("Update apply failed: ${it.javaClass.simpleName}") }
                return@post call.respond(HttpStatusCode.InternalServerError, DaemonErrorBody(failed.error))
            }
            call.respond(body)
        }
    }
}
