package dev.mimir.core.update

import dev.mimir.core.Sha256
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class InPlaceBundleApplierTest {
    private val dir = ".obsidian/plugins/mimir"
    private val mainJs = "console.log('new')".encodeToByteArray()
    private val manifest = """{"id":"mimir","version":"1.2.0","name":"Mimir"}""".encodeToByteArray()
    private val mainUrl = "https://example.test/main.js"
    private val manifestUrl = "https://example.test/manifest.json"

    private fun hex(b: ByteArray) = Sha256.Hasher().also { it.update(b) }.digestHex()

    private fun asset(name: String, url: String, sha: String?) =
        UpdateAsset(name, url, 10, sha, TargetPlatform.OBSIDIAN_ANY, AssetKind.OBSIDIAN_FILES)

    private fun release(mainSha: String? = hex(mainJs), manifestSha: String? = hex(manifest), version: String = "1.2.0"): UpdateRelease {
        val a = listOf(asset("main.js", mainUrl, mainSha), asset("manifest.json", manifestUrl, manifestSha))
        return UpdateRelease(SemVer.parse(version), "", Instant.fromEpochSeconds(0), "https://example.test/r", a)
    }

    private fun http(main: ByteArray = mainJs, mani: ByteArray = manifest) = FakeUpdateHttpClient(
        downloads = mutableMapOf(
            mainUrl to listOf(main.copyOfRange(0, 5), main.copyOfRange(5, main.size)),
            manifestUrl to listOf(mani),
        ),
    )

    private class Disk(val files: MutableMap<String, ByteArray> = mutableMapOf(), var failOn: String? = null) {
        val log = mutableListOf<String>()
        suspend fun write(path: String, bytes: ByteArray) {
            log += path
            if (path == failOn) throw IllegalStateException("disk full")
            files[path] = bytes
        }
        suspend fun read(path: String): ByteArray? = files[path]
    }

    private fun applier(http: UpdateHttpClient, disk: Disk, max: Int = InPlaceBundleApplier.MAX_FILE_BYTES, pluginDir: String = dir) =
        InPlaceBundleApplier(http, pluginDir, "mimir", disk::write, disk::read, max)

    @Test
    fun happyPathWritesBothFilesInOrder() = runTest {
        val disk = Disk()
        val r = release()
        val a = applier(http(), disk)
        val progress = mutableListOf<Float>()
        val bundle = a.download(r.assets[0], r) { f, _, _ -> progress += f }.getOrThrow()
        assertIs<DownloadedAsset.MemoryBundle>(bundle)
        assertTrue(progress.isNotEmpty() && progress.last() <= 1f)
        val result = a.apply(bundle)
        assertEquals(UpdateApplyResult.Success("Plugin updated. Reload Mimir (or restart Obsidian) to finish.", true), result)
        assertEquals(listOf("$dir/main.js", "$dir/manifest.json"), disk.log)
        assertContentEquals(mainJs, disk.files["$dir/main.js"])
        assertContentEquals(manifest, disk.files["$dir/manifest.json"])
    }

    @Test
    fun checksumFromReleaseChecksumsMapIsAccepted() = runTest {
        val r = release(mainSha = null, manifestSha = null).copy(checksums = mapOf("main.js" to hex(mainJs), "manifest.json" to hex(manifest)))
        assertTrue(applier(http(), Disk()).download(r.assets[0], r) { _, _, _ -> }.isSuccess)
    }

    @Test
    fun mainHashMismatchFailsAndWritesNothing() = runTest {
        val disk = Disk()
        val r = release(mainSha = hex("other".encodeToByteArray()))
        val a = applier(http(), disk)
        assertTrue(a.download(r.assets[0], r) { _, _, _ -> }.isFailure)
        assertTrue(disk.log.isEmpty())
    }

    @Test
    fun manifestHashMismatchFails() = runTest {
        val r = release(manifestSha = "00".repeat(32))
        assertTrue(applier(http(), Disk()).download(r.assets[0], r) { _, _, _ -> }.isFailure)
    }

    @Test
    fun missingChecksumIsRefused() = runTest {
        val r = release(manifestSha = null)
        val result = applier(http(), Disk()).download(r.assets[0], r) { _, _, _ -> }
        assertTrue(result.exceptionOrNull()!!.message!!.contains("no checksum"))
    }

    @Test
    fun missingManifestAssetIsRefused() = runTest {
        val r = release().let { it.copy(assets = listOf(it.assets[0])) }
        assertTrue(applier(http(), Disk()).download(r.assets[0], r) { _, _, _ -> }.isFailure)
    }

    @Test
    fun oversizeFileIsRefused() = runTest {
        val r = release()
        val result = applier(http(), Disk(), max = 8).download(r.assets[0], r) { _, _, _ -> }
        assertTrue(result.exceptionOrNull()!!.message!!.contains("too large"))
    }

    @Test
    fun oversizeDeclaredInAssetIsRefusedWithoutDownloading() = runTest {
        val fake = http()
        val r = release().let { it.copy(assets = it.assets.map { a -> a.copy(sizeBytes = 100) }) }
        assertTrue(applier(fake, Disk(), max = 50).download(r.assets[0], r) { _, _, _ -> }.isFailure)
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun manifestVersionMismatchFailsDownload() = runTest {
        val wrong = """{"id":"mimir","version":"9.9.9"}""".encodeToByteArray()
        val r = release(manifestSha = hex(wrong))
        assertTrue(applier(http(mani = wrong), Disk()).download(r.assets[0], r) { _, _, _ -> }.isFailure)
    }

    @Test
    fun manifestIdMismatchFailsDownload() = runTest {
        val wrong = """{"id":"evil","version":"1.2.0"}""".encodeToByteArray()
        val r = release(manifestSha = hex(wrong))
        assertTrue(applier(http(mani = wrong), Disk()).download(r.assets[0], r) { _, _, _ -> }.isFailure)
    }

    @Test
    fun applyRejectsBundleWithWrongManifestAndWritesNothing() = runTest {
        val disk = Disk()
        val bad = DownloadedAsset.MemoryBundle(
            mapOf("main.js" to mainJs, "manifest.json" to """{"id":"evil","version":"1.2.0"}""".encodeToByteArray()),
            release().assets[0],
        )
        assertIs<UpdateApplyResult.Failed>(applier(http(), disk).apply(bad))
        assertTrue(disk.log.isEmpty())
    }

    @Test
    fun applyRejectsVersionDifferentFromDownloadedRelease() = runTest {
        val disk = Disk()
        val r = release()
        val a = applier(http(), disk)
        a.download(r.assets[0], r) { _, _, _ -> }.getOrThrow()
        val other = DownloadedAsset.MemoryBundle(
            mapOf("main.js" to mainJs, "manifest.json" to """{"id":"mimir","version":"0.0.1"}""".encodeToByteArray()),
            r.assets[0],
        )
        assertIs<UpdateApplyResult.Failed>(a.apply(other))
        assertTrue(disk.log.isEmpty())
    }

    @Test
    fun secondWriteFailureRestoresFirstFile() = runTest {
        val oldMain = "old".encodeToByteArray()
        val disk = Disk(mutableMapOf("$dir/main.js" to oldMain, "$dir/manifest.json" to "oldm".encodeToByteArray()), failOn = "$dir/manifest.json")
        val r = release()
        val a = applier(http(), disk)
        val bundle = a.download(r.assets[0], r) { _, _, _ -> }.getOrThrow()
        val result = a.apply(bundle)
        assertIs<UpdateApplyResult.Failed>(result)
        assertContentEquals(oldMain, disk.files["$dir/main.js"])
        assertContentEquals("oldm".encodeToByteArray(), disk.files["$dir/manifest.json"])
    }

    @Test
    fun traversalInBundleOrPluginDirIsImpossible() = runTest {
        val disk = Disk()
        val evil = DownloadedAsset.MemoryBundle(mapOf("../../evil.js" to mainJs, "manifest.json" to manifest), release().assets[0])
        assertIs<UpdateApplyResult.Failed>(applier(http(), disk).apply(evil))
        val ok = DownloadedAsset.MemoryBundle(mapOf("main.js" to mainJs, "manifest.json" to manifest), release().assets[0])
        assertIs<UpdateApplyResult.Failed>(applier(http(), disk, pluginDir = "a/../../b").apply(ok))
        assertTrue(disk.log.isEmpty())
    }

    @Test
    fun capabilityIsInPlace() {
        assertEquals(ApplierCapability.IN_PLACE_REPLACE, applier(http(), Disk()).capability)
    }
}
