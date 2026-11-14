package dev.mimir.obsidian.net

import dev.mimir.core.RemoteUnavailableException
import dev.mimir.obsidian.platform.RequestUrlFn
import dev.mimir.obsidian.platform.UrlRequest
import dev.mimir.obsidian.platform.obsidianRequestUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.callContext
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext

/** `requestUrl` отклонён без статуса HTTP (нет сети, DNS, TLS). [dev.mimir.drive.client.DriveHttpClient] превращает его в RemoteUnavailableException. */
class NetworkUnavailableException(cause: Throwable) : IOException(cause.message, cause)

@OptIn(InternalAPI::class)
class ObsidianHttpEngine(private val request: RequestUrlFn) : HttpClientEngineBase("obsidian") {
    override val config: HttpClientEngineConfig = HttpClientEngineConfig()

    // DriveHttpClient ставит HttpTimeout; requestUrl таймаутов не умеет, поэтому connect/socket-таймауты не действуют.
    override val supportedCapabilities: Set<HttpClientEngineCapability<*>> = setOf(HttpTimeoutCapability)

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val callContext: CoroutineContext = callContext()
        val requestTime = GMTDate()
        val body = data.body.toBytes()
        val headers = LinkedHashMap<String, String>()
        data.headers.forEach { name, values -> headers[name] = values.joinToString(",") }
        data.body.contentType?.let { if (headers.keys.none { k -> k.equals(HttpHeaders.ContentType, true) }) headers[HttpHeaders.ContentType] = it.toString() }
        headers.keys.filter { it.equals(HttpHeaders.ContentLength, true) || it.equals(HttpHeaders.Host, true) }.forEach(headers::remove)

        val response = try {
            request.invoke(UrlRequest(data.url.toString(), data.method.value, headers, body))
        } catch (e: CancellationException) {
            throw e
        } catch (e: NetworkUnavailableException) {
            throw e
        } catch (e: Throwable) {
            throw NetworkUnavailableException(e)
        }

        val responseHeaders = Headers.build {
            response.headers.forEach { (k, v) -> append(k, v) }
        }
        return HttpResponseData(
            HttpStatusCode.fromValue(response.status),
            requestTime,
            responseHeaders,
            HttpProtocolVersion.HTTP_1_1,
            ByteReadChannel(response.body),
            callContext,
        )
    }
}

private suspend fun OutgoingContent.toBytes(): ByteArray? = when (this) {
    is OutgoingContent.NoContent -> null
    is OutgoingContent.ByteArrayContent -> bytes()
    is OutgoingContent.ReadChannelContent -> readFrom().toByteArray()
    is OutgoingContent.WriteChannelContent -> coroutineScope {
        val channel = ByteChannel()
        launch {
            try {
                writeTo(channel)
            } finally {
                channel.flushAndClose()
            }
        }
        channel.toByteArray()
    }
    is OutgoingContent.ContentWrapper -> delegate().toBytes()
    is OutgoingContent.ProtocolUpgrade -> error("Protocol upgrade is not supported")
}

fun obsidianHttpClient(request: RequestUrlFn = obsidianRequestUrl): HttpClient =
    HttpClient(ObsidianHttpEngine(request)) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

internal fun Throwable.isNetworkError(): Boolean {
    if (this is NetworkUnavailableException || this is RemoteUnavailableException || this is IOException) return true
    val msg = message.orEmpty()
    return "Network is down" in msg || "net::" in msg || "Failed to fetch" in msg
}
