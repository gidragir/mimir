package dev.mimir.runtime

import dev.mimir.core.fs.FileSystemLocalVault
import dev.mimir.core.testing.CountingLocalVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.Collections
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Простой демона: без событий ФС в vault нет ни одного обхода `list()` (спека improvements, IO). */
class IdleVaultIoTest {
    private val configDir = Files.createTempDirectory("mimir-idle-cfg")
    private val vaultDir = Files.createTempDirectory("mimir-idle-vault")
    private val vaults = Collections.synchronizedList(mutableListOf<CountingLocalVault>())
    private var runtime: MimirRuntime? = null

    private fun totalLists(): Int = synchronized(vaults) { vaults.sumOf { it.lists } }

    private fun resetLists() = synchronized(vaults) { vaults.forEach { it.reset() } }

    @AfterTest
    fun cleanup() {
        runtime?.close()
        configDir.toFile().deleteRecursively()
        vaultDir.toFile().deleteRecursively()
    }

    private suspend fun start(conflictScanInterval: Duration): MimirRuntime {
        val rt = testRuntime(
            configDir,
            vaultPath = vaultDir.toString(),
            conflictScanInterval = conflictScanInterval,
            localVaultFactory = { path ->
                CountingLocalVault(FileSystemLocalVault(path, ioDispatcher = Dispatchers.IO)).also { vaults += it }
            },
        ).also { runtime = it }
        rt.initialize()
        assertTrue(totalLists() > 0, "начальный поиск конфликтов должен идти через фабрику vault")
        delay(500) // регистрация watcher'а
        resetLists()
        return rt
    }

    @Test
    fun `idle daemon does not list the vault`() = runBlocking {
        start(5.seconds)
        delay(300)
        assertEquals(0, totalLists())
    }

    @Test
    fun `short scan interval does not turn into polling`() = runBlocking {
        start(50.milliseconds)
        delay(300)
        assertEquals(0, totalLists())
    }

    @Test
    fun `file event still refreshes conflicts after idle`() = runBlocking {
        val rt = start(50.milliseconds)
        vaultDir.resolve("Doc (conflicted copy 2026-10-24-120000).md").writeText("x")
        withTimeout(10_000) { rt.conflictsFlow.first { it.isNotEmpty() } }
        assertTrue(totalLists() > 0)
    }
}
