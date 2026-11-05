package dev.mimir.obsidian.update

import dev.mimir.core.update.InPlaceBundleApplier
import dev.mimir.core.update.UpdateApplier
import dev.mimir.core.update.UpdateHttpClient
import dev.mimir.obsidian.DataAdapter
import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array

/**
 * Обёртка [InPlaceBundleApplier] над `app.vault.adapter` (readBinary/writeBinary работают и на телефоне).
 * [pluginDir] — путь каталога плагина относительно хранилища.
 */
class ObsidianInPlaceApplier(
    httpClient: UpdateHttpClient,
    pluginDir: String,
    pluginId: String,
    adapter: DataAdapter,
) : UpdateApplier by InPlaceBundleApplier(
    httpClient = httpClient,
    pluginDir = pluginDir,
    expectedPluginId = pluginId,
    writer = { path, bytes -> adapter.writeBinary(path, bytes.toArrayBuffer()).await() },
    reader = { path -> if (adapter.exists(path).await()) adapter.readBinary(path).await().toBytes() else null },
)

private fun ByteArray.toArrayBuffer(): ArrayBuffer {
    val copy = copyOf().unsafeCast<Int8Array>()
    return copy.buffer.slice(copy.byteOffset, copy.byteOffset + copy.length)
}

private fun ArrayBuffer.toBytes(): ByteArray = Int8Array(this).unsafeCast<ByteArray>().copyOf()
