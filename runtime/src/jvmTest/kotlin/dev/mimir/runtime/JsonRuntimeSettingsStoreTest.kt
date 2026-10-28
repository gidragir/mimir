package dev.mimir.runtime

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JsonRuntimeSettingsStoreTest {
    private val dataDir = Files.createTempDirectory("mimir-settings")

    @AfterTest
    fun cleanup() {
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `defaults without a settings file`() {
        val config = JsonRuntimeSettingsStore(dataDir).load()

        assertEquals(RuntimeConfig(dataDir, vaultPath = null), config)
        assertNull(config.vaultPath)
    }

    @Test
    fun `vault path survives reload`() {
        JsonRuntimeSettingsStore(dataDir).saveVaultPath("/storage/emulated/0/Notes")

        val reloaded = JsonRuntimeSettingsStore(dataDir).load()

        assertEquals("/storage/emulated/0/Notes", reloaded.vaultPath)
        assertEquals(dataDir, reloaded.dataDir)
    }
}
