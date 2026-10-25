package dev.mimir.daemon

import dev.mimir.core.EncryptedRemoteStorage
import dev.mimir.core.InMemoryRemoteStorage
import dev.mimir.core.RemotePath
import dev.mimir.core.RcloneCipher
import dev.mimir.drive.auth.OAuthTokens
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InspectVaultTest {
    private val configDir = Files.createTempDirectory("mimir-inspect-cfg")
    private val vaultDir = Files.createTempDirectory("mimir-inspect-vault")
    private val remote = InMemoryRemoteStorage()
    private var runtime: DaemonRuntime? = null

    @AfterTest
    fun cleanup() {
        runtime?.close()
        configDir.toFile().deleteRecursively()
        vaultDir.toFile().deleteRecursively()
    }

    private fun newRuntime(): DaemonRuntime = DaemonRuntime(
        DaemonConfig(port = 0, password = "secret", googleDeviceClientId = "client-id", syncIntervalSeconds = 3600, configDir = configDir.toString()),
        remoteStorageFactory = { _, _, _ -> remote },
    ).also { runtime = it }

    private suspend fun signIn() {
        FileTokenStorage(configDir.resolve("tokens.json")).save(OAuthTokens("a", "r", 4_000_000_000L))
    }

    @Test
    fun `inspect vault returns 403 when the daemon is locked`() = testApplication {
        val service = FakeDaemonService().apply { isLocked = true }
        application { mimirRoutes(service, DaemonConfig()) }
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/vault/inspect").status)
    }

    @Test
    fun `inspect vault returns counts and requiresStrategyConfirmation`() = testApplication {
        val service = FakeDaemonService().apply {
            isLocked = false
            inspection = VaultInspectionDto(localFileCount = 5, remoteFileCount = 8, requiresStrategyConfirmation = true)
        }
        application { mimirRoutes(service, DaemonConfig()) }
        val response = client.get("/api/v1/vault/inspect")
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("5", json["localFileCount"]?.jsonPrimitive?.content)
        assertEquals("8", json["remoteFileCount"]?.jsonPrimitive?.content)
        assertEquals("true", json["requiresStrategyConfirmation"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST vault responds with the path and the local file count`() = testApplication {
        val service = FakeDaemonService().apply { fileCount = 42 }
        application { mimirRoutes(service, DaemonConfig()) }
        val response = client.post("/api/v1/vault") { contentType(ContentType.Application.Json); setBody("""{"vaultPath":"/ok"}""") }
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("/ok", json["vaultPath"]?.jsonPrimitive?.content)
        assertEquals("42", json["fileCount"]?.jsonPrimitive?.content)
    }

    @Test
    fun `runtime counts local and remote files and asks for confirmation when both exist`() = runBlocking {
        signIn()
        vaultDir.resolve("a.md").writeText("a")
        vaultDir.resolve("b.md").writeText("b")
        val runtime = newRuntime()
        runtime.initialize()

        assertEquals(2, runtime.configureVault(vaultDir.toString()))
        // облако получает файл от другого устройства уже после проверки пароля (канарейка записана)
        EncryptedRemoteStorage(remote, RcloneCipher("secret")).write(RemotePath("r.md"), Buffer().also { it.write("r".encodeToByteArray()) })
        assertEquals(VaultInspectionDto(2, 1, true), runtime.inspectVault())
    }

    @Test
    fun `runtime needs no confirmation when the remote is empty`() = runBlocking {
        signIn()
        vaultDir.resolve("a.md").writeText("a")
        val runtime = newRuntime()
        runtime.initialize()
        runtime.configureVault(vaultDir.toString())

        assertEquals(VaultInspectionDto(1, 0, false), runtime.inspectVault())
    }

    @Test
    fun `inspect fails while the daemon is locked or not connected`() = runBlocking {
        val runtime = DaemonRuntime(DaemonConfig(port = 0, configDir = configDir.toString()), remoteStorageFactory = { _, _, _ -> remote }).also { runtime = it }
        runtime.initialize()
        assertFailsWith<IllegalStateException> { runtime.inspectVault() }
        Unit
    }

    @Test
    fun `sync does not start after first-run setup until the user confirms`() = runBlocking {
        signIn()
        vaultDir.resolve("a.md").writeText("a")
        val runtime = newRuntime()
        runtime.initialize()
        runtime.configureVault(vaultDir.toString())
        assertTrue(runtime.isReady)

        delay(1000)
        assertNull(runtime.lastSyncTime)
        assertTrue(remote.list(RemotePath("")).none { it.path.value != CANARY_FILENAME })

        assertTrue(runtime.triggerSync())
        withTimeout(15_000) { while (runtime.lastSyncTime == null) delay(50) }
        assertTrue(remote.list(RemotePath("")).any { it.path.value != CANARY_FILENAME })
    }
}
