package dev.mimir.daemon

import dev.mimir.core.LocalEntry
import dev.mimir.core.LocalVault
import dev.mimir.core.fs.FileSystemLocalVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
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
    private val listCalls = AtomicInteger()
    private var runtime: DaemonRuntime? = null

    private class CountingLocalVault(private val inner: LocalVault, private val calls: AtomicInteger) : LocalVault by inner {
        override suspend fun list(): List<LocalEntry> {
            calls.incrementAndGet()
            return inner.list()
        }
    }

    @AfterTest
    fun cleanup() {
        runtime?.close()
        configDir.toFile().deleteRecursively()
        vaultDir.toFile().deleteRecursively()
    }

    private suspend fun start(conflictScanInterval: Duration): DaemonRuntime {
        val rt = DaemonRuntime(
            DaemonConfig(vaultPath = vaultDir.toString(), configDir = configDir.toString()),
            conflictScanInterval = conflictScanInterval,
            localVaultFactory = { CountingLocalVault(FileSystemLocalVault(it, ioDispatcher = Dispatchers.IO), listCalls) },
        ).also { runtime = it }
        rt.initialize()
        assertTrue(listCalls.get() > 0, "начальный поиск конфликтов должен идти через фабрику vault")
        delay(500) // регистрация watcher'а
        listCalls.set(0)
        return rt
    }

    @Test
    fun `idle daemon does not list the vault`() = runBlocking {
        start(5.seconds)
        delay(2_000)
        assertEquals(0, listCalls.get())
    }

    @Test
    fun `short scan interval does not turn into polling`() = runBlocking {
        start(50.milliseconds)
        delay(2_000)
        assertEquals(0, listCalls.get())
    }

    @Test
    fun `file event still refreshes conflicts after idle`() = runBlocking {
        val rt = start(50.milliseconds)
        vaultDir.resolve("Doc (conflicted copy 2026-10-24-120000).md").writeText("x")
        withTimeout(10_000) { rt.conflictsFlow.first { it.isNotEmpty() } }
        assertTrue(listCalls.get() > 0)
    }
}
