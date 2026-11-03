package dev.mimir.core.update

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlin.time.Instant

/**
 * Канал обновлений на основе GitHub Releases (`/releases/latest`).
 *
 * Условный запрос: ETag прошлого успешного ответа уходит в `If-None-Match`, `304` = нет обновления.
 * Лимиты API (403/429) возвращаются как [UpdateCheckResult.Error] без повторов.
 */
class GitHubReleasesChannel(
    val owner: String,
    val repo: String,
    val httpClient: UpdateHttpClient,
) : UpdateChannel {
    override val name: String = "github:$owner/$repo"

    private var etag: String? = null

    override suspend fun checkForUpdates(currentVersion: SemVer, targetPlatform: TargetPlatform): UpdateCheckResult =
        try {
            check(currentVersion, targetPlatform)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UpdateCheckResult.Error("Update check failed: ${e.message ?: e::class.simpleName}", e)
        }

    private suspend fun check(currentVersion: SemVer, targetPlatform: TargetPlatform): UpdateCheckResult {
        val url = "https://api.github.com/repos/$owner/$repo/releases/latest"
        val headers = buildMap {
            put("Accept", "application/vnd.github+json")
            etag?.let { put("If-None-Match", it) }
        }
        val response = httpClient.get(url, headers)
        when (response.statusCode) {
            304 -> return UpdateCheckResult.NoUpdate(currentVersion)
            200 -> Unit
            403, 429 -> {
                val retryAfter = response.header("Retry-After")
                val suffix = if (retryAfter != null) ", retry after $retryAfter s" else ""
                return UpdateCheckResult.Error("GitHub API rate limit or access denied (HTTP ${response.statusCode}$suffix)")
            }
            else -> return UpdateCheckResult.Error("GitHub API returned HTTP ${response.statusCode}")
        }

        val root = JSON.parseToJsonElement(response.bodyText).jsonObject
        val version = SemVer.parse(root.string("tag_name"))
        response.header("ETag")?.let { etag = it }
        if (!version.isNewerThan(currentVersion)) return UpdateCheckResult.NoUpdate(currentVersion)

        val rawAssets = (root["assets"] as? JsonArray ?: JsonArray(emptyList())).map { it.jsonObject }
        val checksumsUrl = rawAssets.firstOrNull { it.string("name") == CHECKSUMS_NAME }?.string("browser_download_url")
        val checksums = checksumsUrl?.let { fetchChecksums(it) }.orEmpty()

        val assets = rawAssets.mapNotNull { toAsset(it, checksums) }
        val release = UpdateRelease(
            version = version,
            releaseNotes = (root["body"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
            publishedAt = Instant.parse(root.string("published_at")),
            htmlUrl = root.string("html_url"),
            assets = assets,
            checksums = checksums,
        )
        val matched = assets.firstOrNull { it.platform == targetPlatform && it.isPrimary() }
            ?: assets.firstOrNull { it.platform == targetPlatform }
        return UpdateCheckResult.UpdateAvailable(release, matched)
    }

    /** Сбой загрузки контрольных сумм не валит проверку: хэши остаются неизвестными. */
    private suspend fun fetchChecksums(url: String): Map<String, String> =
        try {
            val r = httpClient.get(url)
            if (r.statusCode == 200) parseChecksums(r.bodyText) else emptyMap()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }

    private fun toAsset(obj: JsonObject, checksums: Map<String, String>): UpdateAsset? {
        val name = obj.string("name")
        val (platform, kind) = classify(name) ?: return null
        return UpdateAsset(
            name = name,
            downloadUrl = obj.string("browser_download_url"),
            sizeBytes = (obj["size"] as? JsonPrimitive)?.long ?: -1L,
            expectedSha256 = checksums[name],
            platform = platform,
            kind = kind,
        )
    }

    private fun UpdateAsset.isPrimary(): Boolean = kind != AssetKind.OBSIDIAN_FILES || name == "main.js"

    private fun classify(name: String): Pair<TargetPlatform, AssetKind>? = when {
        name.endsWith(".msi") -> TargetPlatform.WINDOWS_X64 to AssetKind.MSI
        name.endsWith(".apk") && !name.endsWith("-unsigned.apk") -> TargetPlatform.ANDROID_ANY to AssetKind.APK
        name == "main.js" || name == "manifest.json" -> TargetPlatform.OBSIDIAN_ANY to AssetKind.OBSIDIAN_FILES
        EXTENSION_ZIP.matches(name) -> TargetPlatform.BROWSER_EXTENSION_ANY to AssetKind.EXTENSION_ZIP
        name.endsWith(".pkg.tar.zst") -> TargetPlatform.LINUX_X64 to AssetKind.ARCH_PKG
        else -> null
    }

    private fun UpdateHttpResponse.header(key: String): String? =
        headers.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

    private fun JsonObject.string(key: String): String {
        val p = this[key] as? JsonPrimitive
        require(p != null && p.isString) { "Missing string field '$key'" }
        return p.content
    }

    companion object {
        private const val CHECKSUMS_NAME = "sha256sums.txt"
        private val JSON = Json { ignoreUnknownKeys = true }
        private val EXTENSION_ZIP = Regex(".*extension.*\\.zip")
        private val CHECKSUM_LINE = Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$")

        /** Разбирает `sha256sum`-вывод (`hash  file` и `hash *file`); некорректные строки пропускаются. */
        fun parseChecksums(text: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (line in text.lineSequence()) {
                val m = CHECKSUM_LINE.matchEntire(line.trim()) ?: continue
                out[m.groupValues[2].trim()] = m.groupValues[1].lowercase()
            }
            return out
        }
    }
}
