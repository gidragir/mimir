package dev.mimir.daemon

import dev.mimir.core.RemotePath
import dev.mimir.core.SyncStatus
import dev.mimir.core.label
import dev.mimir.core.InMemoryRemoteStorage
import dev.mimir.drive.auth.OAuthTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DaemonRuntimeTest {
    private val configDir = Files.createTempDirectory("mimir-runtime-cfg")
    private val vaultDir = Files.createTempDirectory("mimir-runtime-vault")
    private val remote = InMemoryRemoteStorage()
    private var runtime: DaemonRuntime? = null

    private fun run(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking { block() }

    @AfterTest
    fun cleanup() {
        runtime?.close()
        configDir.toFile().deleteRecursively()
        vaultDir.toFile().deleteRecursively()
    }

    private fun config(vault: Boolean = true, password: String? = null) = DaemonConfig(
        port = 0,
        vaultPath = vaultDir.toString().takeIf { vault },
        password = password,
        googleClientId = "client-id",
        syncIntervalSeconds = 3600,
        configDir = configDir.toString(),
    )

    private fun runtime(config: DaemonConfig) =
        DaemonRuntime(config, remoteStorageFactory = { _, _, _ -> remote }).also { runtime = it }

    private suspend fun signIn() {
        FileTokenStorage(configDir.resolve("tokens.json")).save(OAuthTokens("a", "r", 4_000_000_000L))
    }

    private suspend fun eventually(block: () -> Boolean) = withTimeout(15_000) {
        while (!block()) delay(50)
    }

    @Test
    fun `without vault or tokens the status is not connected`() = run {
        val runtime = runtime(config(vault = false))
        runtime.initialize()

        assertEquals(SyncStatus.NotConfigured, runtime.status.value)
        assertEquals("Mimir: not connected", runtime.status.value.label())
        assertTrue(runtime.isLocked)
        assertFailsWith<SyncNotConfiguredException> { runtime.triggerSync() }
    }

    @Test
    fun `signed in but locked stays not connected until unlock`() = run {
        signIn()
        val runtime = runtime(config())
        runtime.initialize()
        assertEquals(SyncStatus.NotConfigured, runtime.status.value)
        assertTrue(runtime.isLocked)

        runtime.unlock("secret")

        assertFalse(runtime.isLocked)
        assertTrue(runtime.isReady)
    }

    @Test
    fun `ready runtime encrypts and uploads local notes on manual sync`() = run {
        signIn()
        vaultDir.resolve("Notes").createDirectories()
        vaultDir.resolve("Notes/Idea.md").writeText("hello")
        val runtime = runtime(config(password = "secret"))
        runtime.initialize()
        assertTrue(runtime.isReady)

        eventually { runtime.lastSyncTime != null }

        val rootEntries = remote.list(RemotePath("")).filter { it.path.value != CANARY_FILENAME }
        assertEquals(1, rootEntries.size)
        assertTrue("Notes" !in rootEntries.single().path.value, "names must be encrypted")
        assertNotNull(runtime.lastSyncTime)
    }

    @Test
    fun `starting offline with an unverified password stays locked instead of crashing`() = run {
        signIn()
        val offline = object : dev.mimir.core.ChangeFeedStorage by remote {
            override suspend fun stat(path: RemotePath) = throw java.io.IOException("offline")
        }
        val runtime = DaemonRuntime(config(password = "secret"), remoteStorageFactory = { _, _, _ -> offline }).also { this@DaemonRuntimeTest.runtime = it }
        runtime.initialize()
        assertFalse(runtime.isReady)
    }

    @Test
    fun `unlock reports that the network is needed when the password was never verified`() = run {
        signIn()
        val offline = object : dev.mimir.core.ChangeFeedStorage by remote {
            override suspend fun stat(path: RemotePath) = throw java.io.IOException("offline")
        }
        val runtime = DaemonRuntime(config(), remoteStorageFactory = { _, _, _ -> offline }).also { this@DaemonRuntimeTest.runtime = it }
        runtime.initialize()
        assertFailsWith<SyncNotConfiguredException> { runtime.unlock("secret") }
    }
}
