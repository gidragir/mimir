package dev.mimir.core.update

import dev.mimir.core.Sha256
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Обновление «на месте» для плагина Obsidian: скачивает `main.js` и `manifest.json`, проверяет SHA-256 каждого
 * и манифест (id и версия), затем записывает оба файла через [writer]. Платформенный ввод-вывод внедряется
 * лямбдами, поэтому класс целиком тестируется на JVM.
 *
 * Атомарность: запись начинается только после проверки обоих файлов; если запись упала,
 * уже записанные файлы откатываются старыми байтами из [reader]. `manifest.json` пишется последним.
 * Имена файлов фиксированы ([FILE_NAMES]) — путь из релиза никогда не используется.
 */
class InPlaceBundleApplier(
    private val httpClient: UpdateHttpClient,
    private val pluginDir: String,
    private val expectedPluginId: String,
    private val writer: suspend (path: String, bytes: ByteArray) -> Unit,
    private val reader: suspend (path: String) -> ByteArray?,
    private val maxFileBytes: Int = MAX_FILE_BYTES,
) : UpdateApplier {
    override val capability: ApplierCapability = ApplierCapability.IN_PLACE_REPLACE

    private var pendingVersion: String? = null

    override suspend fun download(
        asset: UpdateAsset,
        release: UpdateRelease,
        onProgress: (Float, Long, Long) -> Unit,
    ): Result<DownloadedAsset> {
        try {
            val sources = FILE_NAMES.map { name ->
                val a = release.assets.firstOrNull { it.name == name && it.kind == AssetKind.OBSIDIAN_FILES }
                    ?: asset.takeIf { it.name == name }
                    ?: return Result.failure(IllegalStateException("Release has no $name"))
                val expected = (a.expectedSha256 ?: release.checksums[name])?.takeIf { it.isNotBlank() }
                    ?: return Result.failure(IllegalStateException("Cannot update: no checksum published for $name"))
                Triple(name, a, expected)
            }
            val files = LinkedHashMap<String, ByteArray>()
            for ((index, source) in sources.withIndex()) {
                val (name, a, expected) = source
                val bytes = fetch(a, name) { read, total ->
                    val part = if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else 0f
                    onProgress((index + part) / sources.size, read, total)
                }
                val actual = Sha256.Hasher().also { it.update(bytes) }.digestHex()
                check(actual.equals(expected, ignoreCase = true)) { "SHA-256 mismatch for $name" }
                files[name] = bytes
            }
            val version = release.version.toString()
            validateManifest(files.getValue(MANIFEST), version)
            pendingVersion = version
            return Result.success(DownloadedAsset.MemoryBundle(files, asset))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return Result.failure(e)
        }
    }

    override suspend fun apply(downloadedAsset: DownloadedAsset): UpdateApplyResult {
        val bundle = downloadedAsset as? DownloadedAsset.MemoryBundle
            ?: return UpdateApplyResult.Failed("Unsupported download type for in-place update")
        val touched = mutableListOf<Pair<String, ByteArray?>>()
        try {
            require(isSafeDir(pluginDir)) { "Unsafe plugin directory" }
            require(bundle.files.keys == FILE_NAMES.toSet()) { "Bundle must contain exactly ${FILE_NAMES.joinToString()}" }
            validateManifest(bundle.files.getValue(MANIFEST), pendingVersion)
            for (name in FILE_NAMES) {
                val path = "$pluginDir/$name"
                touched += path to reader(path)
                writer(path, bundle.files.getValue(name))
            }
            pendingVersion = null
            return UpdateApplyResult.Success("Plugin updated. Reload Mimir (or restart Obsidian) to finish.", requiresRestart = true)
        } catch (e: Throwable) {
            rollback(touched)
            if (e is CancellationException) throw e
            return UpdateApplyResult.Failed(e.message ?: "Update failed", e)
        }
    }

    private suspend fun rollback(touched: List<Pair<String, ByteArray?>>) {
        for ((path, previous) in touched.asReversed()) {
            if (previous == null) continue
            try {
                writer(path, previous)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // откат best-effort: исходная ошибка важнее
            }
        }
    }

    private suspend fun fetch(asset: UpdateAsset, name: String, onProgress: (Long, Long) -> Unit): ByteArray {
        if (asset.sizeBytes > maxFileBytes) error("$name is too large (${asset.sizeBytes} bytes)")
        val chunks = ArrayList<ByteArray>()
        var size = 0
        val result = try {
            httpClient.downloadStream(asset.downloadUrl) { chunk, read, total ->
                if (total > maxFileBytes) throw TooLarge(name)
                size += chunk.size
                if (size > maxFileBytes) throw TooLarge(name)
                chunks += chunk
                onProgress(read, total)
            }
        } catch (e: TooLarge) {
            error("$name is too large")
        }
        result.exceptionOrNull()?.let { throw it }
        val out = ByteArray(size)
        var offset = 0
        for (c in chunks) {
            c.copyInto(out, offset)
            offset += c.size
        }
        return out
    }

    private fun validateManifest(bytes: ByteArray, expectedVersion: String?) {
        val obj = try {
            Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: error("manifest.json is not a valid JSON object")
        val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        check(id == expectedPluginId) { "manifest.json id '$id' does not match installed plugin '$expectedPluginId'" }
        val version = (obj["version"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        check(version != null) { "manifest.json has no version" }
        if (expectedVersion != null) {
            check(version == expectedVersion) { "manifest.json version '$version' does not match release $expectedVersion" }
        }
    }

    private fun isSafeDir(dir: String): Boolean =
        dir.isNotBlank() && '\\' !in dir && !dir.endsWith("/") && dir.split('/').none { it == ".." }

    private class TooLarge(name: String) : RuntimeException("$name is too large")

    companion object {
        const val MAIN_JS = "main.js"
        const val MANIFEST = "manifest.json"
        val FILE_NAMES = listOf(MAIN_JS, MANIFEST)
        const val MAX_FILE_BYTES = 5 * 1024 * 1024
    }
}
