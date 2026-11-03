package dev.mimir.daemon

import dev.mimir.client.DaemonErrorBody
import dev.mimir.client.DaemonService
import dev.mimir.client.DaemonStatusOnlyResponse
import dev.mimir.client.UpdateErrorCode
import dev.mimir.client.applyBlocker
import dev.mimir.client.classifyUpdateError
import dev.mimir.client.downloadBlocker
import dev.mimir.client.disabledUpdateStatus
import dev.mimir.client.toDto
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
import java.util.concurrent.atomic.AtomicBoolean

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

/**
 * `GET /update`, `POST /update/check|download|apply`. Скачивание долгое, поэтому `download` отвечает `202` и работает в
 * фоне, а клиент опрашивает `GET /update` (статус `DOWNLOADING` с `downloadProgress`, затем `READY_TO_INSTALL`
 * или `ERROR` с `errorMessage`) — как `/sync` + `/status`. `apply` быстрый (запуск установщика/уведомление) и
 * отвечает результатом сразу. Без менеджера или при выключенной проверке статус — `DISABLED`, команды дают `409`.
 */
internal fun Route.updateRoutes(service: DaemonService, config: DaemonConfig, manager: UpdateManager?) {
    val currentVersion = BuildConfig.VERSION
    val active = manager?.takeIf { config.updateCheck && it !== DisabledUpdateManager }

    // Атомарный допуск: два одновременных запроса не должны оба получить 202/запуск до смены состояния менеджера.
    val downloadInFlight = AtomicBoolean(false)
    val applyInFlight = AtomicBoolean(false)

    suspend fun ApplicationCall.conflict(code: UpdateErrorCode) =
        respond(HttpStatusCode.Conflict, DaemonErrorBody(code.defaultMessage, code.name))

    route("/update") {
        get {
            val status = active?.state?.value?.toDto(currentVersion) ?: disabledUpdateStatus(currentVersion)
            call.respond(status)
        }

        post("/check") {
            if (!isTrustedCaller(call, config)) return@post call.respond(HttpStatusCode.Forbidden, DaemonErrorBody("Forbidden origin"))
            if (active == null) return@post call.conflict(UpdateErrorCode.UPDATES_DISABLED)
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
                val code = classifyUpdateError(result.message, result.retryAfterSeconds)
                return@post call.respond(HttpStatusCode.BadGateway, DaemonErrorBody(result.message, code.name))
            }
            call.respond(active.state.value.toDto(currentVersion))
        }

        post("/download") {
            if (!isTrustedCaller(call, config)) return@post call.respond(HttpStatusCode.Forbidden, DaemonErrorBody("Forbidden origin"))
            if (active == null) return@post call.conflict(UpdateErrorCode.UPDATES_DISABLED)
            if (!downloadInFlight.compareAndSet(false, true)) return@post call.conflict(UpdateErrorCode.DOWNLOAD_IN_PROGRESS)
            active.state.value.downloadBlocker()?.let {
                downloadInFlight.set(false)
                return@post call.conflict(it)
            }
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
                } finally {
                    downloadInFlight.set(false)
                }
            }
            call.respond(HttpStatusCode.Accepted, DaemonStatusOnlyResponse("started"))
        }

        post("/apply") {
            if (!isTrustedCaller(call, config)) return@post call.respond(HttpStatusCode.Forbidden, DaemonErrorBody("Forbidden origin"))
            if (active == null) return@post call.conflict(UpdateErrorCode.UPDATES_DISABLED)
            if (!applyInFlight.compareAndSet(false, true)) return@post call.conflict(UpdateErrorCode.INSTALL_IN_PROGRESS)
            val result = try {
                active.state.value.applyBlocker(service.status.value == SyncStatus.Syncing)?.let {
                    return@post call.conflict(it)
                }
                active.applyUpdate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warning("Update apply failed: ${e.javaClass.simpleName}")
                UpdateApplyResult.Failed("Update failed")
            } finally {
                applyInFlight.set(false)
            }
            val body = result.toDto()
            if (body == null) {
                val failed = result as UpdateApplyResult.Failed
                failed.cause?.let { logger.warning("Update apply failed: ${it.javaClass.simpleName}") }
                return@post call.respond(HttpStatusCode.InternalServerError, DaemonErrorBody(failed.error, UpdateErrorCode.FAILED.name))
            }
            call.respond(body)
        }
    }
}
