package dev.mimir.core.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import kotlin.js.Promise
import kotlin.js.json

/**
 * [UpdateHttpClient] поверх глобального `fetch` (плагин Obsidian, расширение Chromium).
 * Ответы 304/429 и прочие не-2xx для [get] не бросают исключений; [downloadStream] читает тело чанками
 * и на сетевой ошибке или не-2xx возвращает [Result.failure]. [CancellationException] не глотается.
 */
class FetchUpdateHttpClient(
    private val fetchFn: (url: String, init: dynamic) -> Promise<dynamic> = ::globalFetch,
) : UpdateHttpClient {
    override suspend fun get(url: String, headers: Map<String, String>): UpdateHttpResponse {
        val response = fetchFn(url, requestInit(headers)).await()
        val text = response.text().unsafeCast<Promise<dynamic>>().await() as String
        return UpdateHttpResponse(response.status as Int, headersOf(response), text)
    }

    override suspend fun downloadStream(
        url: String,
        headers: Map<String, String>,
        onChunk: suspend (ByteArray, Long, Long) -> Unit,
    ): Result<Unit> {
        try {
            val response = fetchFn(url, requestInit(headers)).await()
            val status = response.status as Int
            if (status !in 200..299) return Result.failure(IllegalStateException("HTTP $status for $url"))
            val total = (response.headers.get("content-length") as? String)?.toLongOrNull() ?: -1L
            val body = response.body
            var read = 0L
            if (body == null || body == undefined) {
                val bytes = Int8Array(response.arrayBuffer().unsafeCast<Promise<dynamic>>().await().unsafeCast<ArrayBuffer>()).unsafeCast<ByteArray>()
                read = bytes.size.toLong()
                onChunk(bytes, read, if (total >= 0) total else read)
                return Result.success(Unit)
            }
            val reader = body.getReader()
            try {
                while (true) {
                    val step = reader.read().unsafeCast<Promise<dynamic>>().await()
                    if (step.done == true) break
                    val chunk: dynamic = step.value
                    val view = Int8Array(chunk.buffer.unsafeCast<ArrayBuffer>(), chunk.byteOffset.unsafeCast<Int>(), chunk.length.unsafeCast<Int>())
                    val bytes = view.unsafeCast<ByteArray>().copyOf()
                    read += bytes.size
                    onChunk(bytes, read, total)
                }
            } catch (e: Throwable) {
                try {
                    reader.cancel()
                } catch (_: Throwable) {
                }
                throw e
            }
            return Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return Result.failure(e)
        }
    }

    private fun requestInit(headers: Map<String, String>): dynamic {
        val h = json()
        headers.forEach { (k, v) -> h[k] = v }
        return json("method" to "GET", "headers" to h)
    }

    private fun headersOf(response: dynamic): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        response.headers.forEach { value: String, key: String -> out[key.lowercase()] = value }
        return out
    }
}

private fun globalFetch(url: String, init: dynamic): Promise<dynamic> =
    js("globalThis").fetch(url, init).unsafeCast<Promise<dynamic>>()
