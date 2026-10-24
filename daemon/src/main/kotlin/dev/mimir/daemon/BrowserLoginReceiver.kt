package dev.mimir.daemon

import dev.mimir.drive.auth.LoginCallback
import dev.mimir.drive.auth.parseLoginCallback
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Одноразовый loopback-сервер для редиректа OAuth: слушает `127.0.0.1` на свободном порту и принимает
 * `/callback` только с ожидаемым [expectedState]. Запросы с чужим state отвергаются, приёмник ждёт дальше.
 */
class BrowserLoginReceiver(private val timeout: Duration = 5.minutes) : AutoCloseable {
    private val result = CompletableDeferred<LoginCallback>()
    // Останавливается из двух потоков (finally в awaitCallback и close()): getAndSet гарантирует один stop().
    private val server = AtomicReference<EmbeddedServer<*, *>?>(null)

    /** Ожидаемый `state`; задаётся до отдачи пользователю ссылки входа. */
    @Volatile
    var expectedState: String? = null

    var port: Int = 0
        private set

    fun start() {
        val engine = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            routing {
                get("/callback") {
                    val query = call.request.queryParameters.entries().associate { it.key to it.value.first() }
                    val html = ContentType.Text.Html.withCharset(Charsets.UTF_8)
                    when (val parsed = expectedState?.let { parseLoginCallback(query, it) } ?: LoginCallback.Invalid("not ready")) {
                        is LoginCallback.Invalid ->
                            call.respondText("<h1>Неверный запрос</h1>", html, HttpStatusCode.BadRequest)
                        is LoginCallback.Code -> {
                            call.respondText(PAGE_DONE, html)
                            result.complete(parsed)
                        }
                        LoginCallback.Denied -> {
                            call.respondText(PAGE_DENIED, html)
                            result.complete(parsed)
                        }
                    }
                }
            }
        }
        engine.start(wait = false)
        server.set(engine)
        port = kotlinx.coroutines.runBlocking { engine.engine.resolvedConnectors().first().port }
    }

    /** [LoginCallback.Code] или [LoginCallback.Denied]; `null` — тайм-аут. Сервер останавливается в любом случае. */
    suspend fun awaitCallback(): LoginCallback? = try {
        withTimeoutOrNull(timeout) { result.await() }
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { stopServer(graceMillis = 200) }
    }

    override fun close() {
        stopServer(graceMillis = 0)
    }

    private fun stopServer(graceMillis: Long) {
        val current = server.getAndSet(null) ?: return
        current.stop(graceMillis, 1000)
    }

    private companion object {
        const val PAGE_DONE = "<h1>Готово! Можно вернуться в Mimir</h1>"
        const val PAGE_DENIED = "<h1>Вход отменён</h1><p>Вернитесь в Mimir и попробуйте снова.</p>"
    }
}
