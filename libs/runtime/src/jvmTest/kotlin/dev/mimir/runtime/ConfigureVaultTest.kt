package dev.mimir.runtime

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigureVaultTest {
    private val configDir = Files.createTempDirectory("mimir-config-test")

    @Test
    fun `configureVault rejects non-existent directory`() = runTest {
        val settings = MemorySettingsStore(configDir)
        val runtime = testRuntime(configDir, settings = settings)
        try {
            assertFailsWith<IllegalArgumentException> { runtime.configureVault("/non/existent/path/for/sure") }
            assertEquals(null, runtime.vaultPath)
            assertEquals(null, settings.vaultPath)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `configureVault persists the path and exposes existing conflicts`() = runTest {
        val vault = Files.createTempDirectory("mimir-vault-test")
        Files.writeString(vault.resolve("A (conflicted copy 2026-10-24-101010).md"), "x")
        val settings = MemorySettingsStore(configDir)
        val runtime = testRuntime(configDir, settings = settings)
        try {
            runtime.configureVault(vault.toString())
            assertEquals(vault.toString(), runtime.vaultPath)
            assertEquals(vault.toString(), settings.vaultPath)
            assertEquals(listOf("A.md"), runtime.conflictsFlow.value.map { it.originalPath })
        } finally {
            runtime.close()
        }
    }
}
