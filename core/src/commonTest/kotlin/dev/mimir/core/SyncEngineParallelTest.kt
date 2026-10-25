package dev.mimir.core

import dev.mimir.core.sync.DefaultSyncEngine
import dev.mimir.core.sync.InMemorySyncStateStore
import dev.mimir.core.testing.InMemoryLocalVault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Параллельное исполнение плана (спека sync-io §6): предел воркеров, фазы, ошибки, сохранение индекса. */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineParallelTest {

    private fun bufferOf(text: String): Buffer = Buffer().also { it.write(text.encodeToByteArray()) }

    /**
     * Удалённое хранилище с воротами: `write`/`delete` пути ждут его [gates] (или [defaultGate]),
     * считают одновременные вызовы и пишут журнал событий. Тесты идут на одном потоке
     * (`StandardTestDispatcher`), поэтому состояние без синхронизации.
     */
    private class GatedRemote(val inner: InMemoryRemoteStorage = InMemoryRemoteStorage()) : RemoteStorage by inner {
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        var defaultGate: CompletableDeferred<Unit>? = null
        val failWrite = mutableMapOf<String, Exception>()
        val hiddenFromListing = mutableSetOf<String>()
        val events = mutableListOf<String>()
        var active = 0
        var maxActive = 0
        var cancelled = 0
        val statCalls = mutableListOf<String>()

        override suspend fun listAll(root: RemotePath, onProgress: (Int, Int) -> Unit): List<RemoteEntry> =
            inner.listAll(root, onProgress).filterNot { it.path.value in hiddenFromListing }

        override suspend fun stat(path: RemotePath): RemoteEntry? {
            statCalls += path.value
            return inner.stat(path)
        }

        override suspend fun write(path: RemotePath, data: Source): RemoteEntry =
            gated("write", path) { inner.write(path, data) }

        override suspend fun delete(path: RemotePath) =
            gated("delete", path) { inner.delete(path) }

        private suspend fun <T> gated(op: String, path: RemotePath, block: suspend () -> T): T {
            events += "$op-start ${path.value}"
            active++
            maxActive = maxOf(maxActive, active)
            try {
                yield()
                failWrite[path.value]?.takeIf { op == "write" }?.let { throw it }
                (gates[path.value] ?: defaultGate)?.await()
                val result = block()
                events += "$op-end ${path.value}"
                return result
            } catch (e: CancellationException) {
                cancelled++
                throw e
            } finally {
                active--
            }
        }
    }

    private class Fixture(scope: TestScope, parallelism: Int, indexSaveEvery: Int = 100) {
        val vault = InMemoryLocalVault()
        val remote = GatedRemote()
        val store = InMemorySyncStateStore()
        val engine = DefaultSyncEngine(
            vault,
            remote,
            store,
            parallelism = parallelism,
            dispatcher = StandardTestDispatcher(scope.testScheduler),
            indexSaveEvery = indexSaveEvery,
        )
    }

    @Test
    fun transfersNeverExceedParallelism() = runTest {
        val f = Fixture(this, parallelism = 3)
        for (i in 1..20) f.vault.write("n$i.md", bufferOf("note $i"))
        val gate = CompletableDeferred<Unit>()
        f.remote.defaultGate = gate

        val run = launch { f.engine.syncNow() }
        runCurrent()
        assertEquals(3, f.remote.active, "exactly parallelism transfers are in flight")

        gate.complete(Unit)
        run.join()

        assertEquals(3, f.remote.maxActive)
        assertEquals(20, f.remote.events.count { it.startsWith("write-end") })
        assertEquals(20, f.store.load().records.size)
        assertEquals(SyncStatus.Idle, f.engine.status.value)
    }

    @Test
    fun deletesStartOnlyAfterAllTransfers() = runTest {
        val f = Fixture(this, parallelism = 4)
        f.vault.write("old.md", bufferOf("text"))
        f.vault.write("keep.md", bufferOf("keep"))
        f.engine.syncNow()

        // переименование: старый путь исчез, новый появился
        f.vault.deleteByPath("old.md")
        f.vault.write("new.md", bufferOf("text"))
        f.remote.events.clear()
        val gate = CompletableDeferred<Unit>()
        f.remote.gates["new.md"] = gate

        val run = launch { f.engine.syncNow() }
        runCurrent()
        assertTrue("delete-start old.md" !in f.remote.events, "delete must wait for the upload: ${f.remote.events}")
        assertNotNull(f.remote.inner.stat(RemotePath("old.md")))

        gate.complete(Unit)
        run.join()

        val writeEnd = f.remote.events.indexOf("write-end new.md")
        val deleteStart = f.remote.events.indexOf("delete-start old.md")
        assertTrue(writeEnd in 0 until deleteStart, "${f.remote.events}")
        assertNull(f.remote.inner.stat(RemotePath("old.md")))
        assertEquals(setOf("keep.md", "new.md"), f.store.load().records.keys)
    }

    @Test
    fun deleteLocalStillChecksStat() = runTest {
        val f = Fixture(this, parallelism = 4)
        f.vault.write("a.md", bufferOf("A"))
        f.vault.write("b.md", bufferOf("B"))
        f.engine.syncNow()

        // отстающий список: a.md в Drive есть, но в listAll не попал
        f.remote.hiddenFromListing += "a.md"
        f.engine.syncNow()

        assertEquals(listOf("a.md"), f.remote.statCalls)
        assertNotNull(f.vault.getEntry("a.md"))
        assertNotNull(f.store.load().find("a.md"))
        assertEquals(SyncStatus.Idle, f.engine.status.value)
    }

    @Test
    fun fatalErrorCancelsOtherWorkersAndGoesOffline() = runTest {
        val f = Fixture(this, parallelism = 2)
        for (name in listOf("a", "b", "c", "d", "e")) f.vault.write("$name.md", bufferOf(name))
        f.remote.gates["b.md"] = CompletableDeferred() // никогда не открывается
        f.remote.failWrite["c.md"] = RemoteUnavailableException("no net")

        assertFailsWith<RemoteUnavailableException> { f.engine.syncNow() }

        assertEquals(SyncStatus.Offline, f.engine.status.value)
        assertEquals(1, f.remote.cancelled, "the hanging transfer is cancelled: ${f.remote.events}")
        assertTrue(f.remote.events.none { it.endsWith("d.md") || it.endsWith("e.md") }, "${f.remote.events}")
        // a.md успел закончиться; при indexSaveEvery = 100 его запись сохраняет только flush
        assertEquals(setOf("a.md"), f.store.load().records.keys)
        assertEquals(SyncPhase.Finished, assertNotNull(f.engine.progress.value).phase)
    }

    @Test
    fun cancellationFlushesIndex() = runTest {
        val f = Fixture(this, parallelism = 2)
        f.vault.write("a.md", bufferOf("A"))
        f.vault.write("b.md", bufferOf("B"))
        f.remote.gates["b.md"] = CompletableDeferred()

        val run = launch { f.engine.syncNow() }
        runCurrent()
        assertTrue("write-end a.md" in f.remote.events, "${f.remote.events}")
        run.cancelAndJoin()

        assertEquals(SyncStatus.Idle, f.engine.status.value)
        assertEquals(setOf("a.md"), f.store.load().records.keys)
        assertEquals(SyncPhase.Finished, assertNotNull(f.engine.progress.value).phase)
    }

    @Test
    fun finalIndexHasAllRecords() = runTest {
        val f = Fixture(this, parallelism = 4, indexSaveEvery = 7)
        for (i in 1..50) f.vault.write("dir${i % 5}/n$i.md", bufferOf("note $i"))

        f.engine.syncNow()

        val index = f.store.load()
        assertEquals(50, index.records.size)
        assertEquals(4, f.remote.maxActive)
        val p = assertNotNull(f.engine.progress.value)
        assertEquals(50, p.done)
        assertEquals(50, p.completed.size)
        assertTrue(p.problems.isEmpty())

        // следующий прогон ничего не переносит
        f.remote.events.clear()
        f.engine.syncNow()
        assertTrue(f.remote.events.isEmpty(), "${f.remote.events}")
    }

    @Test
    fun parallelismOutsideRangeIsRejected() {
        for (bad in listOf(0, 17)) {
            assertFailsWith<IllegalArgumentException> {
                DefaultSyncEngine(InMemoryLocalVault(), InMemoryRemoteStorage(), InMemorySyncStateStore(), parallelism = bad)
            }
        }
    }

    @Test
    fun problemsFromParallelWorkersAreAllKept() = runTest {
        val f = Fixture(this, parallelism = 4)
        for (i in 1..8) f.vault.write("n$i.md", bufferOf("$i"))
        for (i in 1..8 step 2) f.vault.failReadFor += "n$i.md"

        f.engine.syncNow()

        val p = assertNotNull(f.engine.progress.value)
        assertEquals(setOf("n1.md", "n3.md", "n5.md", "n7.md"), p.problems.map { it.path }.toSet())
        assertEquals(8, p.done)
        assertEquals(setOf("n2.md", "n4.md", "n6.md", "n8.md"), f.store.load().records.keys)
    }
}
