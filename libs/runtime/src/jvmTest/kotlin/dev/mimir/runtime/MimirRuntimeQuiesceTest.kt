package dev.mimir.runtime

import dev.mimir.core.ChangeFeedStorage
import dev.mimir.core.InMemoryRemoteStorage
import dev.mimir.core.RemoteEntry
import dev.mimir.core.RemotePath
import dev.mimir.core.SyncStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** quiesceForUpdate / resumeAfterQuiesce на реальном рантайме (реальное время, ворота вместо advanceUntilIdle). */
class MimirRuntimeQuiesceTest {
    private val dataDir = Files.createTempDirectory("mimir-q-data")
    private val vaultDir = Files.createTempDirectory("mimir-q-vault")
    private val gate = CompletableDeferred<Unit>()
    private var runtime: MimirRuntime? = null

    @AfterTest
    fun cleanup() {
        gate.complete(Unit)
        runtime?.close()
        dataDir.toFile().deleteRecursively()
        vaultDir.toFile().deleteRecursively()
    }

    private fun runtime(blocking: Boolean, watchers: FakeWatcherFactory): MimirRuntime {
        val inner = InMemoryRemoteStorage()
        val remote = if (!blocking) inner else object : ChangeFeedStorage by inner {
            override suspend fun listAll(root: RemotePath, onProgress: (folders: Int, files: Int) -> Unit): List<RemoteEntry> {
                gate.await()
                return inner.listAll(root, onProgress)
            }
        }
        return testRuntime(dataDir, vaultPath = vaultDir.toString(), password = "secret", cloud = { _, _, _ -> remote }, watcherFactory = watchers)
            .also { runtime = it }
    }

    private suspend fun eventually(block: () -> Boolean) = withTimeout(15_000) {
        while (!block()) delay(10)
    }

    @Test
    fun `quiesce waits for the running sync, stops watchers, and resume restarts them`() = runBlocking<Unit> {
        signIn(dataDir)
        val watchers = FakeWatcherFactory()
        val rt = runtime(blocking = true, watchers = watchers)
        rt.initialize()
        eventually { rt.status.value == SyncStatus.Syncing }

        val quiesced = async { rt.quiesceForUpdate(timeout = 30.seconds) }
        delay(150.milliseconds)
        assertFalse(quiesced.isCompleted, "must wait for the in-flight sync")

        gate.complete(Unit)
        assertTrue(quiesced.await())
        eventually { rt.status.value == SyncStatus.Idle }
        assertTrue(watchers.created.any { it.second.stops.get() > 0 })

        rt.resumeAfterQuiesce()
        eventually { watchers.activeCount() >= 1 }
        assertTrue(rt.syncOnce())
    }

    @Test
    fun `quiesce times out on a long sync and leaves the runtime running`() = runBlocking<Unit> {
        signIn(dataDir)
        val watchers = FakeWatcherFactory()
        val rt = runtime(blocking = true, watchers = watchers)
        rt.initialize()
        eventually { rt.status.value == SyncStatus.Syncing }

        assertFalse(rt.quiesceForUpdate(timeout = 200.milliseconds))

        assertTrue(rt.status.value == SyncStatus.Syncing, "the sync was not aborted")
        eventually { watchers.activeCount() >= 1 } // планировщик возобновлён
        gate.complete(Unit)
        eventually { rt.status.value == SyncStatus.Idle }
    }

    @Test
    fun `quiesce on an unconfigured runtime succeeds and resume is a no-op`() = runBlocking<Unit> {
        val rt = testRuntime(dataDir).also { runtime = it }
        rt.initialize()
        assertTrue(rt.quiesceForUpdate(timeout = 1.seconds))
        rt.resumeAfterQuiesce()
    }
}
