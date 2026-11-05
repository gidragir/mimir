package dev.mimir.runtime

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class ConflictCacheTest {
    @Test
    fun `cached conflicts follow files appearing and disappearing in the vault`() = runBlocking {
        val vault = Files.createTempDirectory("mimir-cache-vault")
        val runtime = testRuntime(
            Files.createTempDirectory("mimir-cache-cfg"),
            vaultPath = vault.toString(),
            conflictScanInterval = 50.milliseconds,
        )
        try {
            runtime.initialize()
            assertEquals(emptyList(), runtime.conflictsFlow.value)
            val copy = vault.resolve("N (conflicted copy 2026-10-24-101010).md")
            Files.writeString(copy, "x")
            withTimeout(5_000) { while (runtime.conflictsFlow.value.isEmpty()) delay(20) }
            assertEquals(listOf("N.md"), runtime.conflictsFlow.value.map { it.originalPath })
            Files.delete(copy)
            withTimeout(5_000) { while (runtime.conflictsFlow.value.isNotEmpty()) delay(20) }
        } finally {
            runtime.close()
        }
    }
}
