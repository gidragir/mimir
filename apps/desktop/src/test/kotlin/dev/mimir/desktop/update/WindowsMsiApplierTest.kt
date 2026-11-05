package dev.mimir.desktop.update

import dev.mimir.core.Sha256
import dev.mimir.core.update.AssetKind
import dev.mimir.core.update.DownloadedAsset
import dev.mimir.core.update.SemVer
import dev.mimir.core.update.TargetPlatform
import dev.mimir.core.update.UpdateApplyResult
import dev.mimir.core.update.UpdateAsset
import dev.mimir.core.update.UpdateHttpClient
import dev.mimir.core.update.UpdateHttpResponse
import dev.mimir.core.update.UpdateRelease
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Скриптуемый HTTP-клиент: отдаёт чанки, затем успех/ошибку/ожидание. */
class FakeUpdateHttpClient(
    private val chunks: List<ByteArray> = emptyList(),
    private val outcome: Result<Unit> = Result.success(Unit),
    private val hangAfterChunks: CompletableDeferred<Unit>? = null,
    private val onFirstChunkSent: CompletableDeferred<Unit>? = null,
) : UpdateHttpClient {
    var downloads = 0
    override suspend fun get(url: String, headers: Map<String, String>) = UpdateHttpResponse(200, emptyMap(), "")

    override suspend fun downloadStream(
        url: String,
        headers: Map<String, String>,
        onChunk: suspend (ByteArray, Long, Long) -> Unit,
    ): Result<Unit> {
        downloads++
        val total = chunks.sumOf { it.size }.toLong()
        var read = 0L
        for (c in chunks) {
            read += c.size
            onChunk(c, read, total)
            onFirstChunkSent?.complete(Unit)
        }
        hangAfterChunks?.await()
        return outcome
    }
}

class WindowsMsiApplierTest {
    private val root = Files.createTempDirectory("mimir msi test")
    private val tempDir: Path = root.resolve("updates dir")
    private val content = "msi-bytes".repeat(1000).toByteArray()
    private val sha = Sha256.digest(content).joinToString("") { "%02x".format(it) }
    private val events = mutableListOf<String>()
    private var launched: List<String>? = null
    private var exitCode: Int? = null

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    private fun asset(expected: String? = sha) =
        UpdateAsset("Mimir-0.2.0.msi", "https://example.test/Mimir-0.2.0.msi", content.size.toLong(), expected, TargetPlatform.WINDOWS_X64, AssetKind.MSI)

    private val release = UpdateRelease(SemVer.parse("0.2.0"), "", Instant.fromEpochSeconds(0), "https://example.test/r", emptyList())

    private fun chunked() = content.toList().chunked(1500).map { it.toByteArray() }

    private var quiesceResult = true

    private val host = object : HostLifecycle {
        override suspend fun quiesce(): Boolean { events += "quiesce"; return quiesceResult }
        override suspend fun resume() { events += "resume" }
        override suspend fun shutdown() { events += "shutdown" }
    }

    private fun applier(
        client: UpdateHttpClient = FakeUpdateHttpClient(chunked()),
        launcher: (List<String>) -> Unit = { events += "launch"; launched = it },
    ) = WindowsMsiApplier(
        httpClient = client,
        tempDir = tempDir,
        host = host,
        processLauncher = launcher,
        exit = { events += "exit"; exitCode = it },
    )

    private fun leftovers() = if (Files.exists(tempDir)) Files.list(tempDir).use { it.toList() } else emptyList()

    @Test
    fun `downloads, verifies hash case-insensitively and launches msiexec after shutdown then exits`() = runBlocking<Unit> {
        val progress = mutableListOf<Long>()
        val a = applier()
        val dl = a.download(asset(sha.uppercase()), release) { _, read, _ -> progress += read }.getOrThrow()

        val local = assertIs<DownloadedAsset.LocalFile>(dl)
        assertEquals(sha, local.actualSha256)
        assertTrue(Files.readAllBytes(Path.of(local.localPath)).contentEquals(content))
        assertEquals(content.size.toLong(), progress.last())

        val result = a.apply(local)
        assertIs<UpdateApplyResult.LaunchedInstaller>(result)
        assertEquals(listOf("msiexec", "/i", local.localPath, "/passive"), launched)
        assertTrue(local.localPath.contains(' '), "path with spaces expected")
        assertEquals(listOf("quiesce", "launch", "shutdown", "exit"), events)
        assertEquals(0, exitCode)
    }

    @Test
    fun `hash mismatch deletes the file and fails`() = runBlocking<Unit> {
        val a = applier()
        val r = a.download(asset("0".repeat(64)), release) { _, _, _ -> }
        assertTrue(r.isFailure)
        assertTrue(leftovers().isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test
    fun `missing checksum is refused without any download`() = runBlocking<Unit> {
        val client = FakeUpdateHttpClient(chunked())
        val r = applier(client).download(asset(null), release) { _, _, _ -> }
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("no checksum published"))
        assertEquals(0, client.downloads)
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `stream failure mid-download deletes the partial file`() = runBlocking<Unit> {
        val client = FakeUpdateHttpClient(chunked().take(2), Result.failure(IOException("connection reset")))
        val r = applier(client).download(asset(), release) { _, _, _ -> }
        assertTrue(r.isFailure)
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `cancellation mid-download deletes the partial file`() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val client = FakeUpdateHttpClient(chunked().take(2), hangAfterChunks = CompletableDeferred(), onFirstChunkSent = started)
        val job = async { applier(client).download(asset(), release) { _, _, _ -> } }
        started.await()
        assertEquals(1, leftovers().size)
        job.cancelAndJoin()
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `apply fails and never launches when the file was tampered with after download`() = runBlocking<Unit> {
        val a = applier()
        val local = a.download(asset(), release) { _, _, _ -> }.getOrThrow() as DownloadedAsset.LocalFile
        Files.write(Path.of(local.localPath), "evil".toByteArray())

        val result = a.apply(local)
        assertIs<UpdateApplyResult.Failed>(result)
        assertNull(launched)
        assertEquals(listOf("quiesce", "resume"), events, "bad installer: quiesce undone, daemon never shut down")
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `apply fails when the file vanished`() = runBlocking<Unit> {
        val a = applier()
        val local = a.download(asset(), release) { _, _, _ -> }.getOrThrow() as DownloadedAsset.LocalFile
        Files.delete(Path.of(local.localPath))
        assertIs<UpdateApplyResult.Failed>(a.apply(local))
        assertNull(launched)
        assertEquals(listOf("quiesce", "resume"), events)
    }

    @Test
    fun `quiesce timeout fails without hashing launching or shutting down`() = runBlocking<Unit> {
        val a = applier()
        val local = a.download(asset(), release) { _, _, _ -> }.getOrThrow() as DownloadedAsset.LocalFile
        quiesceResult = false
        val result = a.apply(local)
        assertEquals("A sync is still running, try again later", assertIs<UpdateApplyResult.Failed>(result).error)
        assertNull(launched)
        assertNull(exitCode)
        assertEquals(listOf("quiesce"), events) // quiesce сам возобновляет рантайм при таймауте
        assertTrue(Files.exists(Path.of(local.localPath)), "installer is kept for a retry")
    }

    @Test
    fun `launcher failure yields Failed and no exit`() = runBlocking<Unit> {
        val a = applier(launcher = { throw IOException("msiexec not found") })
        val local = a.download(asset(), release) { _, _, _ -> }.getOrThrow() as DownloadedAsset.LocalFile
        val result = a.apply(local)
        assertIs<UpdateApplyResult.Failed>(result)
        assertNull(exitCode)
        assertEquals(listOf("quiesce", "resume"), events, "launch failure resumes the daemon; no shutdown")
    }

    @Test
    fun `apply rejects a file outside the update directory`() = runBlocking<Unit> {
        val outside = root.resolve("other.msi").also { Files.write(it, content) }
        val result = applier().apply(DownloadedAsset.LocalFile(outside.toString(), sha, asset()))
        assertIs<UpdateApplyResult.Failed>(result)
        assertNull(launched)
    }

    @Test
    fun `apply rejects a non-file download`() = runBlocking<Unit> {
        assertIs<UpdateApplyResult.Failed>(applier().apply(DownloadedAsset.Virtual(asset())))
    }
}
