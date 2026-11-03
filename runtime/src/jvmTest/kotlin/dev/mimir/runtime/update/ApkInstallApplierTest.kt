package dev.mimir.runtime.update

import dev.mimir.core.Sha256
import dev.mimir.core.update.ApplierCapability
import dev.mimir.core.update.AssetKind
import dev.mimir.core.update.DownloadedAsset
import dev.mimir.core.update.SemVer
import dev.mimir.core.update.TargetPlatform
import dev.mimir.core.update.UpdateApplyResult
import dev.mimir.core.update.UpdateAsset
import dev.mimir.core.update.UpdateHttpClient
import dev.mimir.core.update.UpdateRelease
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
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class ApkInstallApplierTest {
    private val root = Files.createTempDirectory("mimir apk test")
    private val dir: Path = root.resolve("cache").resolve("updates")
    private val content = "apk-bytes".repeat(1000).toByteArray()
    private val sha = Sha256.digest(content).joinToString("") { "%02x".format(it) }
    private val events = mutableListOf<String>()
    private var canInstall = true
    private var quiesceResult = true
    private var launchError: Throwable? = null
    private var launchedPath: Path? = null

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    private val launcher = object : ApkInstallLauncher {
        override fun canRequestInstalls() = canInstall
        override fun openInstallPermissionSettings() { events += "settings" }
        override fun launchInstaller(apk: Path) {
            events += "launch"
            launchError?.let { throw it }
            launchedPath = apk
        }
    }

    private val quiesce = object : UpdateQuiesce {
        override suspend fun quiesce(): Boolean { events += "quiesce"; return quiesceResult }
        override suspend fun resume() { events += "resume" }
    }

    private fun applier(client: UpdateHttpClient = ScriptedUpdateHttpClient(chunked())) =
        ApkInstallApplier(client, dir, launcher, quiesce, Dispatchers.IO)

    private fun asset(expected: String? = sha) =
        UpdateAsset("Mimir-0.2.0.apk", "https://example.test/Mimir-0.2.0.apk", content.size.toLong(), expected, TargetPlatform.ANDROID_ANY, AssetKind.APK)

    private val release = UpdateRelease(SemVer.parse("0.2.0"), "", Instant.fromEpochSeconds(0), "https://example.test/r", emptyList())

    private fun chunked() = content.toList().chunked(1500).map { it.toByteArray() }

    private fun leftovers() = if (Files.exists(dir)) Files.list(dir).use { it.toList() } else emptyList()

    private suspend fun downloaded(a: ApkInstallApplier = applier()) =
        a.download(asset(), release) { _, _, _ -> }.getOrThrow() as DownloadedAsset.LocalFile

    @Test
    fun `capability is download and launch`() {
        assertEquals(ApplierCapability.DOWNLOAD_AND_LAUNCH, applier().capability)
    }

    @Test
    fun `downloads into the updates dir, verifies and launches after quiesce`() = runBlocking<Unit> {
        val a = applier()
        val local = downloaded(a)
        assertEquals(sha, local.actualSha256)
        assertEquals(dir.toAbsolutePath(), Path.of(local.localPath).parent.toAbsolutePath())
        assertTrue(local.localPath.endsWith(".apk"))

        val result = a.apply(local)
        assertIs<UpdateApplyResult.LaunchedInstaller>(result)
        assertEquals(listOf("quiesce", "launch"), events)
        assertEquals(Path.of(local.localPath).toAbsolutePath().normalize(), launchedPath)
    }

    @Test
    fun `missing checksum is refused without a download`() = runBlocking<Unit> {
        val client = ScriptedUpdateHttpClient(chunked())
        val r = applier(client).download(asset(null), release) { _, _, _ -> }
        assertTrue(r.exceptionOrNull()!!.message!!.contains("no checksum published"))
        assertEquals(0, client.downloads)
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `hash mismatch deletes the apk`() = runBlocking<Unit> {
        val r = applier().download(asset("0".repeat(64)), release) { _, _, _ -> }
        assertTrue(r.isFailure)
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `partial download is deleted on failure and on cancellation`() = runBlocking<Unit> {
        val failing = ScriptedUpdateHttpClient(chunked().take(2), Result.failure(IOException("reset")))
        assertTrue(applier(failing).download(asset(), release) { _, _, _ -> }.isFailure)
        assertTrue(leftovers().isEmpty())

        val started = CompletableDeferred<Unit>()
        val hanging = ScriptedUpdateHttpClient(chunked().take(2), hangAfterChunks = CompletableDeferred(), onFirstChunkSent = started)
        val job = async { applier(hanging).download(asset(), release) { _, _, _ -> } }
        started.await()
        job.cancelAndJoin()
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `tampered apk is deleted, never launched, and the runtime resumes`() = runBlocking<Unit> {
        val a = applier()
        val local = downloaded(a)
        Files.write(Path.of(local.localPath), "evil".toByteArray())

        val result = a.apply(local)
        assertIs<UpdateApplyResult.Failed>(result)
        assertEquals(listOf("quiesce", "resume"), events)
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `vanished apk fails and resumes`() = runBlocking<Unit> {
        val a = applier()
        val local = downloaded(a)
        Files.delete(Path.of(local.localPath))
        assertIs<UpdateApplyResult.Failed>(a.apply(local))
        assertEquals(listOf("quiesce", "resume"), events)
    }

    @Test
    fun `missing install-unknown-apps permission opens settings and fails with a human message`() = runBlocking<Unit> {
        val a = applier()
        val local = downloaded(a)
        canInstall = false

        val result = assertIs<UpdateApplyResult.Failed>(a.apply(local))
        assertTrue(result.error.contains("Allow", ignoreCase = true), result.error)
        assertEquals(listOf("settings"), events, "no quiesce, no launch")
        assertTrue(Files.exists(Path.of(local.localPath)), "apk kept so the user can retry after granting")
    }

    @Test
    fun `launch exception yields Failed and resumes`() = runBlocking<Unit> {
        val a = applier()
        val local = downloaded(a)
        launchError = IllegalStateException("no activity")

        val result = assertIs<UpdateApplyResult.Failed>(a.apply(local))
        assertTrue(result.error.contains("no activity"))
        assertEquals(listOf("quiesce", "launch", "resume"), events)
    }

    @Test
    fun `sync still running fails without hashing or launching`() = runBlocking<Unit> {
        val a = applier()
        val local = downloaded(a)
        quiesceResult = false

        val result = assertIs<UpdateApplyResult.Failed>(a.apply(local))
        assertEquals("A sync is still running, try again later", result.error)
        assertEquals(listOf("quiesce"), events)
        assertTrue(Files.exists(Path.of(local.localPath)))
    }

    @Test
    fun `apk outside the updates dir and non-file downloads are rejected`() = runBlocking<Unit> {
        val outside = root.resolve("other.apk").also { Files.write(it, content) }
        assertIs<UpdateApplyResult.Failed>(applier().apply(DownloadedAsset.LocalFile(outside.toString(), sha, asset())))
        assertIs<UpdateApplyResult.Failed>(applier().apply(DownloadedAsset.Virtual(asset())))
        assertTrue(events.isEmpty())
    }
}
