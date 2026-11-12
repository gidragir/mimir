package dev.mimir.obsidian.platform

import dev.mimir.obsidian.DataAdapter
import dev.mimir.obsidian.requestUrl
import kotlinx.coroutines.await
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.Source
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
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

data class FsStat(val type: String /* "file"|"folder" */, val size: Long, val mtime: Long)

interface VaultFs {
    suspend fun list(dir: String): Pair<List<String>, List<String>>
    suspend fun stat(path: String): FsStat?
    suspend fun writeBinary(path: String, data: ByteArray)
    suspend fun appendBinary(path: String, data: ByteArray)
    suspend fun readBinary(path: String): ByteArray
    suspend fun rename(from: String, to: String)
    suspend fun remove(path: String)
    suspend fun mkdir(path: String)
    suspend fun exists(path: String): Boolean
}

class AdapterVaultFs(private val adapter: DataAdapter) : VaultFs {
    override suspend fun list(dir: String): Pair<List<String>, List<String>> {
        val res = adapter.list(dir).await()
        val files = (res.files as Array<String>).toList()
        val folders = (res.folders as Array<String>).toList()
        return Pair(files, folders)
    }

    override suspend fun stat(path: String): FsStat? {
        val st = adapter.stat(path).await() ?: return null
        val type = st.type as String
        val size = (st.size as Number).toLong()
        val mtime = (st.mtime as Number).toLong()
        return FsStat(type, size, mtime)
    }

    override suspend fun writeBinary(path: String, data: ByteArray) {
        adapter.writeBinary(path, data.toArrayBuffer()).await()
    }

    override suspend fun appendBinary(path: String, data: ByteArray) {
        adapter.appendBinary(path, data.toArrayBuffer()).await()
    }

    override suspend fun readBinary(path: String): ByteArray {
        val buf = adapter.readBinary(path).await()
        return buf.toByteArray()
    }

    override suspend fun rename(from: String, to: String) {
        adapter.rename(from, to).await()
    }

    override suspend fun remove(path: String) {
        adapter.remove(path).await()
    }

    override suspend fun mkdir(path: String) {
        adapter.mkdir(path).await()
    }

    override suspend fun exists(path: String): Boolean = adapter.exists(path).await()
}

fun interface ResourceStreamFn {
    suspend fun open(path: String): Source
}

class FetchResourceStream(private val adapter: DataAdapter) : ResourceStreamFn {
    override suspend fun open(path: String): Source {
        val uri = adapter.getResourcePath(path)
        val response = kotlinx.browser.window.fetch(uri).await()
        if (!response.ok) {
            throw IOException("Failed to fetch resource: $path (status ${response.status})")
        }
        val buffer = Buffer()
        val body = response.body
        if (body != null) {
            val reader = body.getReader().unsafeCast<dynamic>()
            while (true) {
                val chunk = reader.read().unsafeCast<kotlin.js.Promise<dynamic>>().await()
                if (chunk.done == true) break
                val uint8 = chunk.value.unsafeCast<Uint8Array?>()
                if (uint8 != null && uint8.length > 0) {
                    val int8 = Int8Array(uint8.buffer, uint8.byteOffset, uint8.byteLength)
                    val bytes = int8.unsafeCast<ByteArray>()
                    buffer.write(bytes)
                }
            }
        }
        return buffer
    }
}

sealed interface VaultEvent {
    data class Upsert(val path: String) : VaultEvent
    data class Removed(val path: String) : VaultEvent
    data class Renamed(val from: String, val to: String) : VaultEvent
}


