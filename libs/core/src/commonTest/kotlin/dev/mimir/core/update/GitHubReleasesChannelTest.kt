package dev.mimir.core.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubReleasesChannelTest {
    private val api = "https://api.github.com/repos/mimir-org/mimir/releases/latest"
    private val sums = "https://dl/sha256sums.txt"
    private val h1 = "a".repeat(64)
    private val h2 = "b".repeat(64)

    private fun asset(name: String, url: String = "https://dl/$name", size: Long = 10) =
        """{"name":"$name","browser_download_url":"$url","size":$size}"""

    private fun release(tag: String, vararg assets: String) =
        """{"tag_name":"$tag","body":"Notes","published_at":"2026-11-03T12:00:00Z","html_url":"https://gh/rel","assets":[${assets.joinToString(",")}]}"""

    private fun ok(body: String, headers: Map<String, String> = emptyMap()) = UpdateHttpResponse(200, headers, body)

    private val log = RecordingUpdateLogger()

    private fun channel(client: FakeUpdateHttpClient) = GitHubReleasesChannel("mimir-org", "mimir", client, log)

    private suspend fun check(client: FakeUpdateHttpClient, platform: TargetPlatform = TargetPlatform.WINDOWS_X64) =
        channel(client).checkForUpdates(SemVer.parse("0.1.0"), platform)

    @Test
    fun sameVersionIsNoUpdate() = runTest {
        val c = FakeUpdateHttpClient(responses = mutableMapOf(api to ok(release("v0.1.0"))))
        val r = check(c)
        assertIs<UpdateCheckResult.NoUpdate>(r)
        assertEquals(SemVer.parse("0.1.0"), r.checkedVersion)
    }

    @Test
    fun olderTagIsNoUpdate() = runTest {
        val c = FakeUpdateHttpClient(responses = mutableMapOf(api to ok(release("v0.0.9"))))
        assertIs<UpdateCheckResult.NoUpdate>(check(c))
    }

    @Test
    fun dockerGetsPlaceholderAssetNotListedInReleaseAssets() = runTest {
        val c = FakeUpdateHttpClient(responses = mutableMapOf(api to ok(release("v0.2.0", asset("Mimir-0.2.0.msi")))))
        val r = check(c, TargetPlatform.DOCKER_ANY)
        assertIs<UpdateCheckResult.UpdateAvailable>(r)
        val m = assertNotNull(r.matchedAsset)
        assertEquals("mimir-0.2.0", m.name)
        assertEquals("https://gh/rel", m.downloadUrl)
        assertEquals(0L, m.sizeBytes)
        assertNull(m.expectedSha256)
        assertEquals(AssetKind.DOCKER_TAG, m.kind)
        assertEquals(TargetPlatform.DOCKER_ANY, m.platform)
        assertEquals(listOf("Mimir-0.2.0.msi"), r.release.assets.map { it.name })
    }

    @Test
    fun linuxKeepsRealArchPackageButOtherwiseGetsGenericPlaceholder() = runTest {
        val real = FakeUpdateHttpClient(responses = mutableMapOf(api to ok(release("v0.2.0", asset("mimir-0.2.0-1-x86_64.pkg.tar.zst")))))
        val withPkg = check(real, TargetPlatform.LINUX_X64)
        assertIs<UpdateCheckResult.UpdateAvailable>(withPkg)
        assertEquals(AssetKind.ARCH_PKG, withPkg.matchedAsset?.kind)

        val none = FakeUpdateHttpClient(responses = mutableMapOf(api to ok(release("v0.2.0"))))
        val placeholder = check(none, TargetPlatform.LINUX_X64)
        assertIs<UpdateCheckResult.UpdateAvailable>(placeholder)
        assertEquals(AssetKind.GENERIC, placeholder.matchedAsset?.kind)
    }

    @Test
    fun installerPlatformsWithoutAssetGetNull() = runTest {
        for (platform in listOf(TargetPlatform.WINDOWS_X64, TargetPlatform.WINDOWS_ARM64, TargetPlatform.ANDROID_ANY, TargetPlatform.OBSIDIAN_ANY)) {
            val c = FakeUpdateHttpClient(responses = mutableMapOf(api to ok(release("v0.2.0"))))
            val r = check(c, platform)
            assertIs<UpdateCheckResult.UpdateAvailable>(r)
            assertNull(r.matchedAsset, platform.name)
        }
    }

    @Test
    fun notModifiedReevaluationForDockerKeepsPlaceholder() = runTest {
        val c = FakeUpdateHttpClient(
            responses = mutableMapOf(api to ok(release("v0.2.0"), mapOf("ETag" to "\"e\""))),
            notModifiedWithEtag = mutableSetOf(api),
        )
        val ch = channel(c)
        val first = ch.checkForUpdates(SemVer.parse("0.1.0"), TargetPlatform.DOCKER_ANY)
        val second = ch.checkForUpdates(SemVer.parse("0.1.0"), TargetPlatform.DOCKER_ANY)
        assertEquals("\"e\"", c.requests[1].headers["If-None-Match"])
        assertIs<UpdateCheckResult.UpdateAvailable>(first)
        assertIs<UpdateCheckResult.UpdateAvailable>(second)
        assertEquals(first.matchedAsset, assertNotNull(second.matchedAsset))
        assertEquals(AssetKind.DOCKER_TAG, second.matchedAsset.kind)
    }

    @Test
    fun newerReleaseMatchesMsiWithChecksum() = runTest {
        val c = FakeUpdateHttpClient(
            responses = mutableMapOf(
                api to ok(release("v0.2.0", asset("Mimir-0.2.0.msi", size = 777), asset("sha256sums.txt", sums))),
                sums to ok("$h1  Mimir-0.2.0.msi\n"),
            ),
        )
        val r = check(c)
        assertIs<UpdateCheckResult.UpdateAvailable>(r)
        assertEquals(SemVer.parse("0.2.0"), r.release.version)
        assertEquals("Notes", r.release.releaseNotes)
        assertEquals("https://gh/rel", r.release.htmlUrl)
        assertEquals(kotlin.time.Instant.parse("2026-11-03T12:00:00Z"), r.release.publishedAt)
        val m = assertNotNull(r.matchedAsset)
        assertEquals(AssetKind.MSI, m.kind)
        assertEquals(TargetPlatform.WINDOWS_X64, m.platform)
        assertEquals(h1, m.expectedSha256)
        assertEquals(777L, m.sizeBytes)
        assertEquals(mapOf("Mimir-0.2.0.msi" to h1), r.release.checksums)
    }

    @Test
    fun parsesStarAndTextModeAndIgnoresGarbageLines() = runTest {
        val body = "$h1 *Mimir.apk\n\n# comment\nnot a line\n${h2.uppercase()}  mimir-extension.zip\r\nshort  x.msi\n"
        val c = FakeUpdateHttpClient(
            responses = mutableMapOf(
                api to ok(release("v1.0.0", asset("Mimir.apk"), asset("sha256sums.txt", sums))),
                sums to ok(body),
            ),
        )
        val r = check(c, TargetPlatform.ANDROID_ANY)
        assertIs<UpdateCheckResult.UpdateAvailable>(r)
        assertEquals(h1, r.matchedAsset?.expectedSha256)
        assertEquals(h2, r.release.checksums["mimir-extension.zip"])
        assertNull(r.release.checksums["x.msi"])
    }

    @Test
    fun unsignedApkIsExcluded() = runTest {
        val c = FakeUpdateHttpClient(
            responses = mutableMapOf(api to ok(release("v1.0.0", asset("Mimir-unsigned.apk"), asset("Mimir.apk")))),
        )
        val r = check(c, TargetPlatform.ANDROID_ANY)
        assertIs<UpdateCheckResult.UpdateAvailable>(r)
        assertEquals("Mimir.apk", r.matchedAsset?.name)
        assertTrue(r.release.assets.none { it.name.endsWith("-unsigned.apk") })
    }

    @Test
    fun onlyUnsignedApkGivesNoMatchedAsset() = runTest {
        val c = FakeUpdateHttpClient(
            responses = mutableMapOf(api to ok(release("v1.0.0", asset("Mimir-unsigned.apk")))),
        )
        val r = check(c, TargetPlatform.ANDROID_ANY)
        assertIs<UpdateCheckResult.UpdateAvailable>(r)
        assertNull(r.matchedAsset)
    }

    @Test
    fun obsidianPicksMainJsAndKeepsManifest() = runTest {
        val c = FakeUpdateHttpClient(
            responses = mutableMapOf(
                api to ok(release("v1.0.0", asset("manifest.json"), asset("main.js"), asset("sha256sums.txt", sums))),
                sums to ok("$h1  main.js\n$h2  manifest.json\n"),
            ),
        )
        val r = check(c, TargetPlatform.OBSIDIAN_ANY)
        assertIs<UpdateCheckResult.UpdateAvailable>(r)
        val m = assertNotNull(r.matchedAsset)
        assertEquals("main.js", m.name)
        assertEquals(AssetKind.OBSIDIAN_FILES, m.kind)
        assertEquals(h1, m.expectedSha256)
        val manifest = r.release.assets.single { it.name == "manifest.json" }
        assertEquals(TargetPlatform.OBSIDIAN_ANY, manifest.platform)
        assertEquals(h2, manifest.expectedSha256)
    }

    @Test
    fun extensionAndArchPackagesMatch() = runTest {
        val c = FakeUpdateHttpClient(
            responses = mutableMapOf(
                api to ok(
                    release(
                        "v1.0.0",
                        asset("mimir-browser-extension-1.0.0.zip"),
                        asset("mimir-1.0.0-1-x86_64.pkg.tar.zst"),
                        asset("README.zip"),
                    ),
                ),
            ),
        )
        val ext = check(c, TargetPlatform.BROWSER_EXTENSION_ANY)
        assertIs<UpdateCheckResult.UpdateAvailable>(ext)
        assertEquals(AssetKind.EXTENSION_ZIP, ext.matchedAsset?.kind)
        assertEquals(2, ext.release.assets.size)
        val arch = check(c, TargetPlatform.LINUX_X64)
        assertIs<UpdateCheckResult.UpdateAvailable>(arch)
        assertEquals(AssetKind.ARCH_PKG, arch.matchedAsset?.kind)
        assertEquals(TargetPlatform.LINUX_X64, arch.matchedAsset?.platform)
        val win = check(c, TargetPlatform.WINDOWS_X64)
        assertIs<UpdateCheckResult.UpdateAvailable>(win)
        assertNull(win.matchedAsset)
    }

    @Test
    fun checksumFetchFailureDoesNotFailCheckAndIsNotCached() = runTest {
        val c = FakeUpdateHttpClient(
            responses = mutableMapOf(
                api to ok(release("v0.2.0", asset("Mimir.msi"), asset("sha256sums.txt", sums)), mapOf("ETag" to "\"e\"")),
            ),
            failures = mutableMapOf(sums to RuntimeException("boom")),
            notModifiedWithEtag = mutableSetOf(api),
        )
        val ch = channel(c)
        val r = ch.checkForUpdates(SemVer.parse("0.1.0"), TargetPlatform.WINDOWS_X64)
        assertIs<UpdateCheckResult.UpdateAvailable>(r)
        assertNull(r.matchedAsset?.expectedSha256)
        assertTrue(r.release.checksums.isEmpty())
        val entry = log.entries.single()
        assertTrue(sums in entry.message && "boom" in entry.message, entry.message)
        assertEquals("boom", entry.cause?.message)
        // сбой чек-сумм: ETag не сохранён, следующий опрос полный и повторно тянет sha256sums.txt
        c.failures.clear()
        c.responses[sums] = ok("$h1  Mimir.msi\n")
        val again = ch.checkForUpdates(SemVer.parse("0.1.0"), TargetPlatform.WINDOWS_X64)
        assertNull(c.requests[1].headers["If-None-Match"])
        assertIs<UpdateCheckResult.UpdateAvailable>(again)
        assertEquals(h1, again.matchedAsset?.expectedSha256)
    }

    @Test
    fun checksumHttpErrorDoesNotFailCheck() = runTest {
        val c = FakeUpdateHttpClient(
            responses = mutableMapOf(
                api to ok(release("v0.2.0", asset("Mimir.msi"), asset("sha256sums.txt", sums))),
                sums to UpdateHttpResponse(500, emptyMap(), "err"),
            ),
        )
        val r = check(c)
        assertIs<UpdateCheckResult.UpdateAvailable>(r)
        assertNull(r.matchedAsset?.expectedSha256)
        assertTrue("HTTP 500" in log.entries.single().message, log.entries.single().message)
    }

    @Test
    fun noChecksumsAssetMeansNullHashes() = runTest {
        val c = FakeUpdateHttpClient(responses = mutableMapOf(api to ok(release("v0.2.0", asset("Mimir.msi")))))
        val r = check(c)
        assertIs<UpdateCheckResult.UpdateAvailable>(r)
        assertNull(r.matchedAsset?.expectedSha256)
        assertEquals(1, c.requests.size)
    }

    @Test
    fun rateLimit403And429AreErrorsWithoutRetry() = runTest {
        for (code in listOf(403, 429)) {
            val c = FakeUpdateHttpClient(
                responses = mutableMapOf(api to UpdateHttpResponse(code, mapOf("Retry-After" to "60"), "rate limited")),
            )
            val r = check(c)
            assertIs<UpdateCheckResult.Error>(r)
            assertTrue("60" in r.message, r.message)
            assertEquals(1, c.requests.size, "no retry for $code")
        }
    }

    @Test
    fun rateLimitHeaderLookupIsCaseInsensitiveAndOptional() = runTest {
        val lower = FakeUpdateHttpClient(
            responses = mutableMapOf(api to UpdateHttpResponse(429, mapOf("retry-after" to "30"), "")),
        )
        val r1 = check(lower)
        assertIs<UpdateCheckResult.Error>(r1)
        assertTrue("30" in r1.message)
        val none = FakeUpdateHttpClient(responses = mutableMapOf(api to UpdateHttpResponse(403, emptyMap(), "")))
        assertIs<UpdateCheckResult.Error>(check(none))
    }

    @Test
    fun otherHttpErrorIsError() = runTest {
        val c = FakeUpdateHttpClient(responses = mutableMapOf(api to UpdateHttpResponse(500, emptyMap(), "")))
        val r = check(c)
        assertIs<UpdateCheckResult.Error>(r)
        assertTrue("500" in r.message)
    }

    @Test
    fun malformedJsonIsError() = runTest {
        for (body in listOf("{not json", "[]", """{"tag_name":"v1.0.0"}""", release("garbage"))) {
            val r = check(FakeUpdateHttpClient(responses = mutableMapOf(api to ok(body))))
            assertIs<UpdateCheckResult.Error>(r, body)
        }
    }

    @Test
    fun networkExceptionIsErrorWithCause() = runTest {
        val boom = RuntimeException("offline")
        val r = check(FakeUpdateHttpClient(failures = mutableMapOf(api to boom)))
        assertIs<UpdateCheckResult.Error>(r)
        assertEquals(boom, r.cause)
    }

    @Test
    fun cancellationIsRethrown() = runTest {
        val c = FakeUpdateHttpClient(failures = mutableMapOf(api to CancellationException("cancel")))
        assertFailsWith<CancellationException> { check(c) }
    }

    @Test
    fun etagIsSentAndNotModifiedIsNoUpdate() = runTest {
        val c = FakeUpdateHttpClient(
            responses = mutableMapOf(api to ok(release("v0.2.0"), mapOf("ETag" to "\"tag-1\""))),
        )
        val ch = channel(c)
        assertIs<UpdateCheckResult.NoUpdate>(ch.checkForUpdates(SemVer.parse("0.2.0"), TargetPlatform.WINDOWS_X64))
        assertNull(c.requests[0].headers["If-None-Match"])
        c.responses[api] = UpdateHttpResponse(304, emptyMap(), "")
        val r = ch.checkForUpdates(SemVer.parse("0.2.0"), TargetPlatform.WINDOWS_X64)
        assertEquals("\"tag-1\"", c.requests[1].headers["If-None-Match"])
        assertIs<UpdateCheckResult.NoUpdate>(r)
    }

    @Test
    fun etagHeaderLookupIsCaseInsensitive() = runTest {
        val c = FakeUpdateHttpClient(
            responses = mutableMapOf(api to ok(release("v0.2.0"), mapOf("etag" to "W/\"x\""))),
        )
        val ch = channel(c)
        ch.checkForUpdates(SemVer.parse("0.1.0"), TargetPlatform.WINDOWS_X64)
        ch.checkForUpdates(SemVer.parse("0.1.0"), TargetPlatform.WINDOWS_X64)
        assertEquals("W/\"x\"", c.requests[1].headers["If-None-Match"])
    }

    @Test
    fun pendingUpdateSurvivesNotModified() = runTest {
        val c = FakeUpdateHttpClient(
            responses = mutableMapOf(
                api to ok(release("v0.2.0", asset("Mimir.msi"), asset("sha256sums.txt", sums)), mapOf("ETag" to "\"e\"")),
                sums to ok("$h1  Mimir.msi\n"),
            ),
        )
        val ch = channel(c)
        val first = ch.checkForUpdates(SemVer.parse("0.1.0"), TargetPlatform.WINDOWS_X64)
        assertIs<UpdateCheckResult.UpdateAvailable>(first)
        c.responses[api] = UpdateHttpResponse(304, emptyMap(), "")
        val second = ch.checkForUpdates(SemVer.parse("0.1.0"), TargetPlatform.WINDOWS_X64)
        assertIs<UpdateCheckResult.UpdateAvailable>(second)
        assertEquals(first.release, second.release)
        assertEquals(h1, second.matchedAsset?.expectedSha256)
        // другая платформа и другая версия не используют устаревший результат
        val android = ch.checkForUpdates(SemVer.parse("0.1.0"), TargetPlatform.ANDROID_ANY)
        assertIs<UpdateCheckResult.UpdateAvailable>(android)
        assertNull(android.matchedAsset)
        assertIs<UpdateCheckResult.NoUpdate>(ch.checkForUpdates(SemVer.parse("0.2.0"), TargetPlatform.WINDOWS_X64))
        assertEquals(1, c.requestsTo(sums).size)
    }

    @Test
    fun notModifiedAfterNoUpdateIsNoUpdateUntilCurrentVersionDrops() = runTest {
        val c = FakeUpdateHttpClient(
            responses = mutableMapOf(api to ok(release("v0.2.0", asset("Mimir.msi")), mapOf("ETag" to "\"e\""))),
            notModifiedWithEtag = mutableSetOf(api),
        )
        val ch = channel(c)
        assertIs<UpdateCheckResult.NoUpdate>(ch.checkForUpdates(SemVer.parse("0.2.0"), TargetPlatform.WINDOWS_X64))
        // 304 по снимку не-нового релиза
        assertIs<UpdateCheckResult.NoUpdate>(ch.checkForUpdates(SemVer.parse("0.3.0"), TargetPlatform.WINDOWS_X64))
        assertEquals(2, c.requests.size)
        assertEquals("\"e\"", c.requests[1].headers["If-None-Match"])
        // версия понизилась: сервер ответит 304, но снимок без релиза не подходит -> безусловный повтор
        val r = ch.checkForUpdates(SemVer.parse("0.1.0"), TargetPlatform.WINDOWS_X64)
        assertEquals(4, c.requests.size)
        assertEquals("\"e\"", c.requests[2].headers["If-None-Match"])
        assertNull(c.requests[3].headers["If-None-Match"])
        assertIs<UpdateCheckResult.UpdateAvailable>(r)
    }

    @Test
    fun endlessNotModifiedIsBoundedError() = runTest {
        val c = FakeUpdateHttpClient(
            responses = mutableMapOf(api to ok(release("v0.2.0", asset("Mimir.msi")), mapOf("ETag" to "\"e\""))),
        )
        val ch = channel(c)
        assertIs<UpdateCheckResult.NoUpdate>(ch.checkForUpdates(SemVer.parse("0.2.0"), TargetPlatform.WINDOWS_X64))
        c.alwaysNotModified += api
        val r = ch.checkForUpdates(SemVer.parse("0.1.0"), TargetPlatform.WINDOWS_X64)
        assertIs<UpdateCheckResult.Error>(r)
        assertEquals(3, c.requests.size) // 1 исходный + conditional + один безусловный повтор
        // без снимка первый же 304 - тоже Error, без рекурсии
        val fresh = FakeUpdateHttpClient(alwaysNotModified = mutableSetOf(api))
        assertIs<UpdateCheckResult.Error>(check(fresh))
        assertEquals(1, fresh.requests.size)
    }

    @Test
    fun retryAfterSecondsFromRetryAfterHeader() = runTest {
        val c = FakeUpdateHttpClient(
            responses = mutableMapOf(api to UpdateHttpResponse(429, mapOf("Retry-After" to "60"), "")),
        )
        val r = check(c)
        assertIs<UpdateCheckResult.Error>(r)
        assertEquals(60L, r.retryAfterSeconds)
    }

    @Test
    fun retryAfterSecondsFallsBackToRateLimitReset() = runTest {
        val reset = kotlin.time.Clock.System.now().epochSeconds + 120
        val c = FakeUpdateHttpClient(
            responses = mutableMapOf(api to UpdateHttpResponse(403, mapOf("X-RateLimit-Reset" to "$reset"), "")),
        )
        val r = check(c)
        assertIs<UpdateCheckResult.Error>(r)
        val s = assertNotNull(r.retryAfterSeconds)
        assertTrue(s in 100L..120L, "$s")
        val none = check(FakeUpdateHttpClient(responses = mutableMapOf(api to UpdateHttpResponse(403, emptyMap(), ""))))
        assertIs<UpdateCheckResult.Error>(none)
        assertNull(none.retryAfterSeconds)
    }

    @Test
    fun malformedAssetIsSkippedNotFatal() = runTest {
        val bad = """{"name":"Bad.msi","browser_download_url":123,"size":"x"}"""
        val c = FakeUpdateHttpClient(
            responses = mutableMapOf(api to ok(release("v0.2.0", bad, asset("Mimir.msi")))),
        )
        val r = check(c)
        assertIs<UpdateCheckResult.UpdateAvailable>(r)
        assertEquals("Mimir.msi", r.matchedAsset?.name)
        assertEquals(1, r.release.assets.size)
    }
}
