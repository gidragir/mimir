package dev.mimir.runtime

import dev.mimir.core.InMemoryRemoteStorage
import dev.mimir.core.testing.TestCiphers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class EngineAssemblerTest {
    private val dataDir = Files.createTempDirectory("mimir-assembler-data")
    private val vaultDir = Files.createTempDirectory("mimir-assembler-vault")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = jsonHttpClient()
    private val cloudCalls = AtomicInteger()
    private val remote = InMemoryRemoteStorage()

    @AfterTest
    fun cleanup() {
        scope.cancel()
        http.close()
        dataDir.toFile().deleteRecursively()
        vaultDir.toFile().deleteRecursively()
    }

    private fun assembler(watchers: VaultWatcherFactory = FakeWatcherFactory()) = EngineAssembler(
        httpClient = http,
        cloudFactory = { _, _, _ -> cloudCalls.incrementAndGet(); remote },
        watcherFactory = watchers,
        ioDispatcher = Dispatchers.IO,
        scope = scope,
        config = { RuntimeConfig(dataDir, vaultDir.toString(), syncIntervalSeconds = 3600) },
    )

    @Test
    fun `returns null without credentials`() = runBlocking {
        val assembled = assembler().assemble(vaultDir.toString(), TestCiphers.forPassword("secret"), FakeTokenSource(signedIn = false))

        assertNull(assembled)
        assertEquals(0, cloudCalls.get(), "no cloud client without credentials")
    }

    @Test
    fun `uses watcher from factory`() = runBlocking {
        val watchers = FakeWatcherFactory()

        val assembled = assertNotNull(assembler(watchers).assemble(vaultDir.toString(), TestCiphers.forPassword("secret"), FakeTokenSource()))

        val (root, watcher) = watchers.created.single()
        assertEquals(vaultDir.toString(), root.toString())
        assertSame(watcher, assembled.watcher)
        assertEquals(1, cloudCalls.get())
    }
}
