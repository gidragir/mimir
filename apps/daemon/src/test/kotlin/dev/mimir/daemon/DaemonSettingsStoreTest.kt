package dev.mimir.daemon

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DaemonSettingsStoreTest {
    private val configDir = Files.createTempDirectory("mimir-daemon-settings")

    @AfterTest
    fun cleanup() {
        configDir.toFile().deleteRecursively()
    }

    @Test
    fun `save and reload daemon config`() {
        DaemonConfig(vaultPath = "/test/vault", configDir = configDir.toString()).save(configDir.toString())
        val loaded = DaemonConfig.load(configDir = configDir.toString(), env = emptyMap())
        assertEquals("/test/vault", loaded.vaultPath)
    }

    @Test
    fun `runtime config follows daemon config`() {
        val store = DaemonSettingsStore(
            DaemonConfig(vaultPath = "/v", syncIntervalSeconds = 60, syncParallelism = 8, rootFolder = "Root", configDir = configDir.toString()),
        )

        val config = store.load()

        assertEquals(Paths.get(configDir.toString()), config.dataDir)
        assertEquals("/v", config.vaultPath)
        assertEquals(60, config.syncIntervalSeconds)
        assertEquals(8, config.syncParallelism)
        assertEquals("Root", config.rootFolder)
    }

    @Test
    fun `saving the vault updates config json and memory without environment secrets`() {
        val store = DaemonSettingsStore(DaemonConfig(configDir = configDir.toString(), password = "from-env", token = "tok"))

        store.saveVaultPath("/vault")

        assertEquals("/vault", store.load().vaultPath)
        assertEquals("/vault", DaemonConfig.load(configDir.toString(), emptyMap()).vaultPath)
        val text = Files.readString(configDir.resolve("config.json"))
        assertFalse("from-env" in text || "tok" in text, text)
    }
}
