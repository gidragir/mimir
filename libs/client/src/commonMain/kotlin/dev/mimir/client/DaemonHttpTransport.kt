package dev.mimir.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Клиент с таймаутами: зависший демон не должен вешать опрос и кнопки интерфейса. */
internal fun HttpClientConfig<*>.installDaemonTimeouts() {
    install(HttpTimeout) {
        connectTimeoutMillis = 1_000
        requestTimeoutMillis = 5_000
    }
}

internal fun defaultDaemonHttpClient(): HttpClient = HttpClient { installDaemonTimeouts() }

internal enum class SseStreamOutcome { Unsupported, Ended, Failed }

/**
 * Низкоуровневый HTTP-транспорт клиента демона: авторизация заголовком, JSON-сериализация,
 * потоковое чтение SSE и преобразование HTTP-ошибок демона в исключения домена.
 */
internal class DaemonHttpTransport(
    private val baseUrl: String,
    private val token: String?,
    val httpClient: HttpClient,
    val json: Json,
) {
    suspend fun get(path: String, configure: HttpRequestBuilder.() -> Unit = {}): HttpResponse =
        httpClient.get("$baseUrl$path") {
            authorize()
            configure()
        }

    suspend fun post(path: String): HttpResponse =
        httpClient.post("$baseUrl$path") { authorize() }

    suspend inline fun <reified B : Any> post(path: String, body: B): HttpResponse =
        httpClient.post("$baseUrl$path") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(body))
        }

    suspend inline fun <reified T> getJson(path: String): T {
        val response = get(path)
        if (response.status != HttpStatusCode.OK) throw response.toException()
        return json.decodeFromString(response.bodyAsText())
    }

    suspend inline fun <reified R> postJson(path: String): R {
        val response = post(path)
        if (response.status != HttpStatusCode.OK) throw response.toException()
        return json.decodeFromString(response.bodyAsText())
    }

    suspend inline fun <reified B : Any, reified R> postJson(path: String, body: B): R {
        val response = post(path, body)
        if (response.status != HttpStatusCode.OK) throw response.toException()
        return json.decodeFromString(response.bodyAsText())
    }

    suspend fun postExpect(path: String, expected: HttpStatusCode = HttpStatusCode.OK) {
        val response = post(path)
        if (response.status != expected) throw response.toException()
    }

    suspend inline fun <reified B : Any> postExpect(path: String, body: B, expected: HttpStatusCode = HttpStatusCode.OK) {
        val response = post(path, body)
        if (response.status != expected) throw response.toException()
    }

    suspend fun streamSse(path: String, onPayload: suspend (String) -> Unit): SseStreamOutcome = try {
        httpClient.prepareGet("$baseUrl$path") {
            authorize()
            header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
            if (httpClient.pluginOrNull(HttpTimeout) != null) {
                timeout {
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                }
            }
        }.execute { response ->
            if (response.status != HttpStatusCode.OK ||
                response.contentType()?.withoutParameters() != ContentType.Text.EventStream
            ) {
                return@execute SseStreamOutcome.Unsupported
            }
            val channel = response.bodyAsChannel()
            val dataLines = mutableListOf<String>()
            while (true) {
                val line = channel.readLine()
                if (line == null) {
                    if (dataLines.isNotEmpty()) onPayload(dataLines.joinToString("\n"))
                    break
                }
                when {
                    line.isEmpty() -> if (dataLines.isNotEmpty()) {
                        onPayload(dataLines.joinToString("\n"))
                        dataLines.clear()
                    }
                    line.startsWith("data:") -> {
                        val value = line.removePrefix("data:")
                        dataLines += if (value.startsWith(" ")) value.drop(1) else value
                    }
                }
            }
            SseStreamOutcome.Ended
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        SseStreamOutcome.Failed
    }

    suspend fun HttpResponse.toException(): Exception {
        val body = runCatching { json.decodeFromString<DaemonErrorBody>(bodyAsText()) }.getOrNull()
        val message = body?.error ?: "Daemon replied $status"
        UpdateErrorCode.parse(body?.code)?.let { return UpdateCommandException(it, message) }
        return when (status) {
            HttpStatusCode.Forbidden -> InvalidPasswordException(message)
            HttpStatusCode.BadRequest -> IllegalArgumentException(message)
            HttpStatusCode.NotFound -> NoSuchElementException(message)
            HttpStatusCode.PreconditionFailed -> SyncNotConfiguredException(message)
            else -> IllegalStateException(message)
        }
    }

    fun close() {
        httpClient.close()
    }

    private fun HttpRequestBuilder.authorize() {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }
}
