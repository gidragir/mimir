package dev.mimir.runtime.login

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
import kotlinx.coroutines.CoroutineDispatcher
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
class BrowserLoginReceiver(
    /** Где останавливается сервер (блокирующий вызов); JVM/Android передают `Dispatchers.IO`. */
    private val ioDispatcher: CoroutineDispatcher,
    private val timeout: Duration = 5.minutes,
    private val redirectScheme: String? = "mimir",
) : AutoCloseable {
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
                            call.respondText(renderSuccessPage(redirectScheme), html)
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
        withContext(NonCancellable + ioDispatcher) { stopServer(graceMillis = 200) }
    }

    override fun close() {
        stopServer(graceMillis = 0)
    }

    private fun stopServer(graceMillis: Long) {
        val current = server.getAndSet(null) ?: return
        current.stop(graceMillis, 1000)
    }

    private companion object {
        const val PAGE_DENIED = "<h1>Вход отменён</h1><p>Вернитесь в Mimir и попробуйте снова.</p>"

        private fun renderSuccessPage(scheme: String?): String {
            val redirectScript = if (scheme != null) {
                """<script>window.location.replace("$scheme://authorized");</script>"""
            } else ""
            val manualButton = if (scheme != null) {
                """<a href="$scheme://authorized" class="btn">Открыть Mimir</a>"""
            } else ""

            return """<!DOCTYPE html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <title>Mimir — Вход выполнен</title>
  <style>
    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; 
           display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; 
           background: #1e1e1e; color: #dcddde; }
    .card { background: #262626; padding: 32px; border-radius: 8px; border: 1px solid #333; text-align: center; max-width: 400px; }
    h1 { font-size: 20px; margin-bottom: 12px; color: #ffffff; }
    p { font-size: 14px; color: #999; line-height: 1.5; margin-bottom: 24px; }
    .btn { display: inline-block; background: #705dcf; color: white; padding: 10px 20px; 
           border-radius: 6px; text-decoration: none; font-weight: 500; font-size: 14px; }
    .hint { font-size: 12px; color: #888; line-height: 1.4; margin-top: 20px; margin-bottom: 0; }
  </style>
</head>
<body>
  <div class="card">
    <h1>Готово!</h1>
    <p>Авторизация успешно завершена. Перенаправляем вас обратно в приложение Mimir…</p>
    $manualButton
    <p class="hint">Если приложение не открылось автоматически, нажмите кнопку выше или переключитесь в окно Mimir вручную.</p>
  </div>
  $redirectScript
</body>
</html>"""
        }
    }
}
