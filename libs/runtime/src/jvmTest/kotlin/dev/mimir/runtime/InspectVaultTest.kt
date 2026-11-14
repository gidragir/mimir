package dev.mimir.runtime

import dev.mimir.client.DaemonVaultInspectionDto as VaultInspectionDto
import dev.mimir.core.EncryptedRemoteStorage
import dev.mimir.core.InMemoryRemoteStorage
import dev.mimir.core.RemotePath
import dev.mimir.core.testing.TestCiphers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
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
    private var runtime: MimirRuntime? = null

    @AfterTest
    fun cleanup() {
        runtime?.close()
        configDir.toFile().deleteRecursively()
        vaultDir.toFile().deleteRecursively()
    }

    private fun newRuntime(): MimirRuntime =
        testRuntime(configDir, password = "secret", cloud = { _, _, _ -> remote }).also { runtime = it }

    private suspend fun signIn() = dev.mimir.runtime.signIn(configDir)

    @Test
    fun `runtime counts local and remote files and asks for confirmation when both exist`() = runBlocking {
        signIn()
        vaultDir.resolve("a.md").writeText("a")
        vaultDir.resolve("b.md").writeText("b")
        val runtime = newRuntime()
        runtime.initialize()

        assertEquals(2, runtime.configureVault(vaultDir.toString()))
        // облако получает файл от другого устройства уже после проверки пароля (канарейка записана)
        EncryptedRemoteStorage(remote, TestCiphers.forPassword("secret")).write(RemotePath("r.md"), Buffer().also { it.write("r".encodeToByteArray()) })
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
        val runtime = testRuntime(configDir, cloud = { _, _, _ -> remote }).also { runtime = it }
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
