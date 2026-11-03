package dev.mimir.runtime.update

import dev.mimir.core.update.UpdateHttpClient
import dev.mimir.core.update.UpdateHttpResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException

/**
 * Адаптер [UpdateHttpClient] поверх Ktor. `get` возвращает любой статус (включая 304/429) и не бросает на не-2xx;
 * `downloadStream` читает тело чанками через канал, не буферизуя файл целиком. Временные файлы — забота аппликера.
 */
class KtorUpdateHttpClient(
    private val client: HttpClient,
    private val chunkSize: Int = DEFAULT_CHUNK,
) : UpdateHttpClient {
    override suspend fun get(url: String, headers: Map<String, String>): UpdateHttpResponse {
        val response = client.get(url) { headers.forEach { (k, v) -> header(k, v) } }
        return UpdateHttpResponse(
            statusCode = response.status.value,
            headers = response.headers.entries().associate { (name, values) -> name to values.joinToString(",") },
            bodyText = response.bodyAsText(),
        )
    }

    override suspend fun downloadStream(
        url: String,
        headers: Map<String, String>,
        onChunk: suspend (ByteArray, Long, Long) -> Unit,
    ): Result<Unit> = try {
        client.prepareGet(url) { headers.forEach { (k, v) -> header(k, v) } }.execute { response ->
            if (!response.status.isSuccess()) {
                return@execute Result.failure(IllegalStateException("HTTP ${response.status.value} for $url"))
            }
            val total = response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1L
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(chunkSize)
            var read = 0L
            while (true) {
                val n = channel.readAvailable(buffer, 0, buffer.size)
                if (n < 0) break
                if (n == 0) continue
                read += n
                onChunk(buffer.copyOf(n), read, total)
            }
            if (total >= 0 && read != total) {
                Result.failure(IllegalStateException("Incomplete download: $read of $total bytes"))
            } else {
                Result.success(Unit)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private companion object {
        const val DEFAULT_CHUNK = 64 * 1024
    }
}
