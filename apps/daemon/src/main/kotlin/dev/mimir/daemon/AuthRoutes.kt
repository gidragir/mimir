package dev.mimir.daemon

import dev.mimir.client.DaemonAuthUrlResponse
import dev.mimir.client.DaemonAuthorizedResponse
import dev.mimir.client.DaemonErrorBody
import dev.mimir.client.DaemonExpiredResponse
import dev.mimir.client.DaemonFailedResponse
import dev.mimir.client.DaemonLoginMethod
import dev.mimir.client.DaemonLoginPoll
import dev.mimir.client.DaemonLoginStart
import dev.mimir.client.DaemonMethodsResponse
import dev.mimir.client.DaemonStatusOnlyResponse
import dev.mimir.client.SyncNotConfiguredException
import dev.mimir.client.wireName
import dev.mimir.drive.client.DriveHttpException
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** Маршруты входа в Google (`/api/v1/auth`): JSON-контракт сохраняется для плагина и web-ui. */
internal fun Route.authRoutes(service: DaemonService) {
    post("/auth/device/start") {
        try {
            val start = service.startLogin(DaemonLoginMethod.DeviceCode)
            val code = (start as? DaemonLoginStart.ShowDeviceCode)?.code
                ?: return@post call.respond(
                    HttpStatusCode.InternalServerError,
                    DaemonErrorBody("Unexpected sign-in start: $start"),
                )
            call.respond(code)
        } catch (e: SyncNotConfiguredException) {
            call.respond(HttpStatusCode.PreconditionFailed, DaemonErrorBody(e.message ?: "Not configured"))
        } catch (e: DriveHttpException) {
            call.respond(HttpStatusCode.BadGateway, DaemonErrorBody(e.message ?: "Google request failed"))
        }
    }

    get("/auth/device/poll") {
        // Код устройства обязателен по контракту API; опрашивается попытка, начатая через /auth/device/start.
        call.request.queryParameters["device_code"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, DaemonErrorBody("device_code is required"))
        try {
            when (val result = service.pollLogin()) {
                DaemonLoginPoll.Pending -> call.respond(DaemonStatusOnlyResponse("pending"))
                is DaemonLoginPoll.Authorized -> call.respond(DaemonAuthorizedResponse("authorized", result.email))
                is DaemonLoginPoll.Failed -> call.respond(
                    HttpStatusCode.BadRequest,
                    DaemonExpiredResponse("expired", "Code has expired"),
                )
            }
        } catch (e: SyncNotConfiguredException) {
            call.respond(HttpStatusCode.PreconditionFailed, DaemonErrorBody(e.message ?: "Not configured"))
        } catch (e: DriveHttpException) {
            call.respond(HttpStatusCode.BadGateway, DaemonErrorBody(e.message ?: "Google request failed"))
        }
    }

    get("/auth/methods") {
        call.respond(DaemonMethodsResponse(service.loginMethods().sorted().map { it.wireName }))
    }

    post("/auth/browser/start") {
        try {
            val start = service.startLogin(DaemonLoginMethod.Browser)
            val url = (start as? DaemonLoginStart.OpenUrl)?.url
                ?: return@post call.respond(
                    HttpStatusCode.InternalServerError,
                    DaemonErrorBody("Unexpected sign-in start: $start"),
                )
            call.respond(DaemonAuthUrlResponse(url))
        } catch (_: SyncNotConfiguredException) {
            call.respond(HttpStatusCode.Conflict, DaemonErrorBody("Browser sign-in is not available"))
        }
    }

    get("/auth/browser/poll") {
        when (val result = service.pollLogin()) {
            DaemonLoginPoll.Pending -> call.respond(DaemonStatusOnlyResponse("pending"))
            is DaemonLoginPoll.Authorized -> call.respond(DaemonAuthorizedResponse("authorized", result.email))
            is DaemonLoginPoll.Failed -> call.respond(DaemonFailedResponse("failed", result.reason))
        }
    }
}
