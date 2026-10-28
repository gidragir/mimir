package dev.mimir.daemon

import dev.mimir.runtime.FileTokenStorage

import dev.mimir.core.EncryptedRemoteStorage
import dev.mimir.core.InMemoryRemoteStorage
import dev.mimir.core.RcloneCipher
import dev.mimir.core.RemotePath
import dev.mimir.drive.auth.OAuthTokens
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DaemonFullCycleE2ETest {
    private val configDir = Files.createTempDirectory("mimir-e2e-cfg")
    private val vaultDir = Files.createTempDirectory("mimir-e2e-vault")
    private val remote = InMemoryRemoteStorage()
    private val cleartextRemote = EncryptedRemoteStorage(remote, kotlinx.coroutines.runBlocking { RcloneCipher.create("secret") })
    private var runtime: dev.mimir.runtime.MimirRuntime? = null

    @AfterTest
    fun cleanup() {
        runtime?.close()
        configDir.toFile().deleteRecursively()
        vaultDir.toFile().deleteRecursively()
    }

    private suspend fun eventually(what: String, check: suspend () -> Boolean) {
        try {
            withTimeout(30_000) { while (!check()) delay(100) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for: $what", e)
        }
    }

    private suspend fun readRemote(path: String) =
        cleartextRemote.read(RemotePath(path)).readByteArray().decodeToString()

    @Test
    fun `local edits reach the cloud encrypted, cloud edits reach the vault, and nothing echoes back`() = testApplication {
        FileTokenStorage(configDir.resolve("tokens.json")).save(OAuthTokens("a", "r", 4_000_000_000L))
        val config = DaemonConfig(
            vaultPath = vaultDir.toString(),
            password = "secret",
            googleDeviceClientId = "cid",
            syncIntervalSeconds = 1,
            configDir = configDir.toString(),
        )
        val runtime = createRuntime(config, cloudFactory = { _, _, _ -> remote }).also { runtime = it }
        runtime.initialize()
        application { mimirRoutes(runtime, config) }
        eventually("initial sync") { runtime.lastSyncTime != null }

        // 1. локальная заметка -> watcher -> sync -> зашифрованные блоки в облаке
        vaultDir.resolve("Notes").createDirectories()
        vaultDir.resolve("Notes/Idea.md").writeText("local idea")
        eventually("upload") { remote.list(RemotePath("")).isNotEmpty() }
        eventually("encrypted content") { runCatching { readRemote("Notes/Idea.md") }.getOrNull() == "local idea" }
        val rawNames = remote.list(RemotePath("")).map { it.path.value }
        assertEquals(false, rawNames.any { "Notes" in it }, "names in the cloud must be encrypted: $rawNames")

        // 2. файл появился в облаке -> Change Feed -> расшифрованная заметка в vault
        cleartextRemote.write(RemotePath("Remote/Hello.md"), Buffer().also { it.write("from cloud".encodeToByteArray()) })
        eventually("download") { vaultDir.resolve("Remote/Hello.md").exists() }
        assertEquals("from cloud", vaultDir.resolve("Remote/Hello.md").readText())

        // 3. загруженный из облака файл не возвращается обратно (echo suppression)
        val revisionAfterDownload = assertNotNull(cleartextRemote.stat(RemotePath("Remote/Hello.md"))).revision
        delay(4_000)
        assertEquals(revisionAfterDownload, cleartextRemote.stat(RemotePath("Remote/Hello.md"))?.revision)
        assertEquals("from cloud", vaultDir.resolve("Remote/Hello.md").readText())

        // 4. POST /api/v1/sync -> 202 и обновлённый lastSyncAt
        fun lastSyncAt(body: String) = Json.parseToJsonElement(body).jsonObject["lastSyncAt"]?.jsonPrimitive?.content
        val before = lastSyncAt(client.get("/api/v1/status").bodyAsText())
        assertNotNull(before)
        delay(1_100)
        var accepted = client.post("/api/v1/sync")
        while (accepted.status == HttpStatusCode.Conflict) { // идёт плановый прогон
            delay(100)
            accepted = client.post("/api/v1/sync")
        }
        assertEquals(HttpStatusCode.Accepted, accepted.status)
        eventually("lastSyncAt update") { lastSyncAt(client.get("/api/v1/status").bodyAsText()) != before }
    }
}
