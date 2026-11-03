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

    private fun channel(client: FakeUpdateHttpClient) = GitHubReleasesChannel("mimir-org", "mimir", client)

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
    fun checksumFetchFailureDoesNotFailCheck() = runTest {
        val c = FakeUpdateHttpClient(
            responses = mutableMapOf(api to ok(release("v0.2.0", asset("Mimir.msi"), asset("sha256sums.txt", sums)))),
            failures = mutableMapOf(sums to RuntimeException("boom")),
        )
        val r = check(c)
        assertIs<UpdateCheckResult.UpdateAvailable>(r)
        assertNull(r.matchedAsset?.expectedSha256)
        assertTrue(r.release.checksums.isEmpty())
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
        assertIs<UpdateCheckResult.UpdateAvailable>(ch.checkForUpdates(SemVer.parse("0.1.0"), TargetPlatform.WINDOWS_X64))
        assertNull(c.requests[0].headers["If-None-Match"])
        c.responses[api] = UpdateHttpResponse(304, emptyMap(), "")
        val r = ch.checkForUpdates(SemVer.parse("0.1.0"), TargetPlatform.WINDOWS_X64)
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
}
