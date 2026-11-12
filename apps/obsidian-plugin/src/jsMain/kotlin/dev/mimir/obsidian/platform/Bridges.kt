package dev.mimir.obsidian.platform

import dev.mimir.obsidian.requestUrl
import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import kotlin.js.json

class UrlRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
)

class UrlResponse(
    val status: Int,
    /** Ключи в нижнем регистре. */
    val headers: Map<String, String>,
    val body: ByteArray,
)

/** HTTP через `requestUrl` Obsidian (обходит CORS на всех платформах). В тестах подменяется фейком. */
fun interface RequestUrlFn {
    suspend fun invoke(request: UrlRequest): UrlResponse
}

internal fun ByteArray.toArrayBuffer(): ArrayBuffer {
    val view = unsafeCast<Int8Array>()
    return view.buffer.slice(view.byteOffset, view.byteOffset + view.length)
}

internal fun ArrayBuffer.toByteArray(): ByteArray = Int8Array(this).unsafeCast<ByteArray>()

val obsidianRequestUrl: RequestUrlFn = RequestUrlFn { request ->
    val headers = json()
    request.headers.forEach { (k, v) -> headers[k] = v }
    val param = json(
        "url" to request.url,
        "method" to request.method,
        "headers" to headers,
        "throw" to false,
    )
    request.body?.let { param["body"] = it.toArrayBuffer() }
    val response = requestUrl(param).await()
    val outHeaders = HashMap<String, String>()
    val raw = response.headers
    val keys = js("Object.keys")(raw).unsafeCast<Array<String>>()
    for (key in keys) outHeaders[key.lowercase()] = raw[key].toString()
    UrlResponse(response.status, outHeaders, response.arrayBuffer.toByteArray())
}

/** Хранилище на устройстве (вне файлов vault). В тестах подменяется фейком. */
interface LocalStorageFn {
    fun load(key: String): String?
    fun save(key: String, value: String?)
}

/** Реализация [LocalStorageFn] поверх API приложения Obsidian. */
class AppLocalStorage(private val app: dev.mimir.obsidian.App) : LocalStorageFn {
    override fun load(key: String): String? {
        val value = app.loadLocalStorage(key)
        return if (value == null || value == "null" || value == "undefined") null else value
    }

    override fun save(key: String, value: String?) {
        app.saveLocalStorage(key, value)
    }
}

