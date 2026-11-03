package dev.mimir.core.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlin.time.Clock
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

    /** Неизменяемый снимок одного успешного ответа: ETag, версия и (если она новее) релиз с чек-суммами. */
    private class Snapshot(val etag: String, val version: SemVer, val release: UpdateRelease?)

    private val lock = Mutex()
    private var snapshot: Snapshot? = null

    override suspend fun checkForUpdates(currentVersion: SemVer, targetPlatform: TargetPlatform): UpdateCheckResult =
        try {
            check(currentVersion, targetPlatform, allowConditional = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UpdateCheckResult.Error("Update check failed: ${e.message ?: e::class.simpleName}", e)
        }

    private suspend fun check(
        currentVersion: SemVer,
        targetPlatform: TargetPlatform,
        allowConditional: Boolean,
    ): UpdateCheckResult {
        val url = "https://api.github.com/repos/$owner/$repo/releases/latest"
        val sent = if (allowConditional) lock.withLock { snapshot } else null
        val headers = buildMap {
            put("Accept", "application/vnd.github+json")
            sent?.let { put("If-None-Match", it.etag) }
        }
        val response = httpClient.get(url, headers)
        when (response.statusCode) {
            304 -> {
                if (sent == null) return UpdateCheckResult.Error("GitHub API returned unexpected HTTP 304 to an unconditional request")
                val cached = sent.release
                if (cached != null) return resultFor(cached, currentVersion, targetPlatform)
                // Закэширован не-новый релиз: ответ верен, пока currentVersion не ниже его.
                if (!sent.version.isNewerThan(currentVersion)) return UpdateCheckResult.NoUpdate(currentVersion)
                // Снимок не подходит запросу: один безусловный повтор (без рекурсии дальше).
                return check(currentVersion, targetPlatform, allowConditional = false)
            }
            200 -> Unit
            403, 429 -> {
                val retryAfter = response.header("Retry-After")
                val suffix = if (retryAfter != null) ", retry after $retryAfter s" else ""
                return UpdateCheckResult.Error(
                    "GitHub API rate limit or access denied (HTTP ${response.statusCode}$suffix)",
                    retryAfterSeconds = retryAfterSeconds(response),
                )
            }
            else -> return UpdateCheckResult.Error("GitHub API returned HTTP ${response.statusCode}")
        }

        val root = JSON.parseToJsonElement(response.bodyText).jsonObject
        val version = SemVer.parse(root.string("tag_name"))

        val rawAssets = (root["assets"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }
        val isNewer = version.isNewerThan(currentVersion)
        val checksumsUrl = if (!isNewer) null else rawAssets
            .firstOrNull { (it["name"] as? JsonPrimitive)?.content == CHECKSUMS_NAME }
            ?.let { (it["browser_download_url"] as? JsonPrimitive)?.content }
        val fetched = checksumsUrl?.let { fetchChecksums(it) }
        val checksums = fetched.orEmpty()

        // Один битый ассет не валит всю проверку.
        val assets = rawAssets.mapNotNull { runCatching { toAsset(it, checksums) }.getOrNull() }
        val release = UpdateRelease(
            version = version,
            releaseNotes = (root["body"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
            publishedAt = Instant.parse(root.string("published_at")),
            htmlUrl = root.string("html_url"),
            assets = assets,
            checksums = checksums,
        )
        // Снимок только после полного разбора; при сбое загрузки чек-сумм не кэшируем, чтобы
        // следующий опрос был полным и не раздавал релиз без хэшей по 304.
        val checksumsFailed = checksumsUrl != null && fetched == null
        val etag = response.header("ETag")
        lock.withLock {
            snapshot = if (etag != null && !checksumsFailed) {
                Snapshot(etag, version, release.takeIf { isNewer })
            } else {
                null
            }
        }
        if (!isNewer) return UpdateCheckResult.NoUpdate(currentVersion)
        return resultFor(release, currentVersion, targetPlatform)
    }

    private fun resultFor(release: UpdateRelease, currentVersion: SemVer, targetPlatform: TargetPlatform): UpdateCheckResult {
        if (!release.version.isNewerThan(currentVersion)) return UpdateCheckResult.NoUpdate(currentVersion)
        val matched = release.assets.firstOrNull { it.platform == targetPlatform && it.isPrimary() }
            ?: release.assets.firstOrNull { it.platform == targetPlatform }
            ?: placeholderAsset(release, targetPlatform)
        return UpdateCheckResult.UpdateAvailable(release, matched)
    }

    /**
     * Платформы только с уведомлением (Docker, Linux-пакеты, расширение) обходятся без файла: возвращаем
     * виртуальный ассет со ссылкой на релиз. Он не входит в `release.assets`. Для Windows/Android/Obsidian
     * нужен настоящий установщик, поэтому там `null`.
     */
    private fun placeholderAsset(release: UpdateRelease, targetPlatform: TargetPlatform): UpdateAsset? {
        val kind = when (targetPlatform) {
            TargetPlatform.DOCKER_ANY -> AssetKind.DOCKER_TAG
            TargetPlatform.LINUX_X64, TargetPlatform.LINUX_ARM64,
            TargetPlatform.BROWSER_EXTENSION_ANY, TargetPlatform.UNKNOWN,
            -> AssetKind.GENERIC
            else -> return null
        }
        return UpdateAsset("mimir-${release.version}", release.htmlUrl, 0L, null, targetPlatform, kind)
    }

    /** `Retry-After` в секундах; иначе `X-RateLimit-Reset` (epoch-секунды) минус сейчас. HTTP-date не разбирается. */
    private fun retryAfterSeconds(response: UpdateHttpResponse): Long? {
        response.header("Retry-After")?.trim()?.toLongOrNull()?.let { return it.coerceAtLeast(0) }
        val reset = response.header("X-RateLimit-Reset")?.trim()?.toLongOrNull() ?: return null
        return (reset - Clock.System.now().epochSeconds).coerceAtLeast(0)
    }

    /** Сбой загрузки контрольных сумм не валит проверку: хэши остаются неизвестными. */
    private suspend fun fetchChecksums(url: String): Map<String, String>? =
        try {
            val r = httpClient.get(url)
            if (r.statusCode == 200) parseChecksums(r.bodyText) else null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
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
