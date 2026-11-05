package dev.mimir.runtime.update

import dev.mimir.core.Sha256
import dev.mimir.core.update.AssetKind
import dev.mimir.core.update.TargetPlatform
import dev.mimir.core.update.UpdateAsset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VerifiedFileDownloaderTest {
    private val root = Files.createTempDirectory("mimir dl test")
    private val dir: Path = root.resolve("updates dir")
    private val content = "payload".repeat(1000).toByteArray()
    private val sha = Sha256.digest(content).joinToString("") { "%02x".format(it) }

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    private fun asset(expected: String? = sha) =
        UpdateAsset("file.bin", "https://example.test/file.bin", content.size.toLong(), expected, TargetPlatform.ANDROID_ANY, AssetKind.APK)

    private fun chunked() = content.toList().chunked(1500).map { it.toByteArray() }

    private fun downloader(client: ScriptedUpdateHttpClient) = VerifiedFileDownloader(client, dir, "mimir-update-", ".bin", Dispatchers.IO)

    private fun leftovers() = if (Files.exists(dir)) Files.list(dir).use { it.toList() } else emptyList()

    @Test
    fun `downloads, reports progress and verifies hash case-insensitively`() = runBlocking<Unit> {
        val progress = mutableListOf<Long>()
        val local = downloader(ScriptedUpdateHttpClient(chunked())).download(asset(sha.uppercase())) { _, read, _ -> progress += read }.getOrThrow()
        assertEquals(sha, local.actualSha256)
        assertTrue(Files.readAllBytes(Path.of(local.localPath)).contentEquals(content))
        assertEquals(content.size.toLong(), progress.last())
        assertEquals(sha, VerifiedFileDownloader.sha256Of(Path.of(local.localPath)))
    }

    @Test
    fun `mismatch deletes the file`() = runBlocking<Unit> {
        val r = downloader(ScriptedUpdateHttpClient(chunked())).download(asset("0".repeat(64))) { _, _, _ -> }
        assertTrue(r.isFailure)
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `missing or blank checksum is refused without a request`() = runBlocking<Unit> {
        for (expected in listOf(null, " ")) {
            val client = ScriptedUpdateHttpClient(chunked())
            val r = downloader(client).download(asset(expected)) { _, _, _ -> }
            assertTrue(r.exceptionOrNull()!!.message!!.contains("no checksum published"))
            assertEquals(0, client.downloads)
        }
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `stream failure deletes the partial file`() = runBlocking<Unit> {
        val client = ScriptedUpdateHttpClient(chunked().take(2), Result.failure(IOException("reset")))
        assertTrue(downloader(client).download(asset()) { _, _, _ -> }.isFailure)
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `cancellation deletes the partial file`() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val client = ScriptedUpdateHttpClient(chunked().take(2), hangAfterChunks = CompletableDeferred(), onFirstChunkSent = started)
        val job = async { downloader(client).download(asset()) { _, _, _ -> } }
        started.await()
        assertEquals(1, leftovers().size)
        job.cancelAndJoin()
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `sweepStale removes only old files matching the glob`() {
        Files.createDirectories(dir)
        val old = Files.createFile(dir.resolve("mimir-update-old.apk"))
        val fresh = Files.createFile(dir.resolve("mimir-update-new.apk"))
        val other = Files.createFile(dir.resolve("mimir-update-old.msi"))
        val now = java.time.Instant.now()
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.from(now.minus(java.time.Duration.ofDays(3))))
        Files.setLastModifiedTime(other, java.nio.file.attribute.FileTime.from(now.minus(java.time.Duration.ofDays(3))))

        assertEquals(1, VerifiedFileDownloader.sweepStale(dir, "mimir-update-*.apk", now = now))
        assertTrue(!Files.exists(old))
        assertTrue(Files.exists(fresh))
        assertTrue(Files.exists(other))
        assertEquals(0, VerifiedFileDownloader.sweepStale(root.resolve("missing"), "*"))
    }
}
