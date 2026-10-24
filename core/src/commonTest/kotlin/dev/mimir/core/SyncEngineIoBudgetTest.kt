package dev.mimir.core

import dev.mimir.core.sync.DefaultSyncEngine
import dev.mimir.core.sync.InMemorySyncStateStore
import dev.mimir.core.testing.InMemoryLocalVault
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Бюджет запросов к облаку на прогон (спека sync-io §11–§12): стоимость растёт от числа изменений, а не от N. */
class SyncEngineIoBudgetTest {

    private fun bufferOf(text: String): Buffer = Buffer().also { it.write(text.encodeToByteArray()) }

    /** Vault, считающий локальные записи. */
    private class CountingVault(private val inner: InMemoryLocalVault = InMemoryLocalVault()) : LocalVault by inner {
        var writes = 0
            private set

        override suspend fun write(relativePath: String, data: Source): LocalEntry {
            writes++
            return inner.write(relativePath, data)
        }

        override suspend fun write(id: VaultFileId, data: Source) {
            writes++
            inner.write(id, data)
        }

        fun resetWrites() {
            writes = 0
        }
    }

    private class Fixture {
        val vault = CountingVault()
        val inner = InMemoryRemoteStorage()
        val remote = CountingRemoteStorage(inner)
        val store = InMemorySyncStateStore()
        val engine = DefaultSyncEngine(vault, remote, store)

        fun reset() {
            remote.reset()
            vault.resetWrites()
        }
    }

    private suspend fun Fixture.syncedVault(n: Int = N) {
        for (i in 1..n) vault.write("notes/n$i.md", Buffer().also { it.write("note $i".encodeToByteArray()) })
        engine.syncNow()
        assertEquals(n, store.load().records.size)
        reset()
    }

    private suspend fun LocalVault.text(path: String): String =
        read(assertNotNull(getEntry(path)).id).readByteArray().decodeToString()

    @Test
    fun unchangedRunReadsAndWritesNothing() = runTest {
        val f = Fixture()
        f.syncedVault()

        f.engine.syncNow()

        assertEquals(1, f.remote.listAllCalls)
        assertEquals(0, f.remote.readCalls)
        assertEquals(0, f.remote.statCalls)
        assertEquals(0, f.remote.writeCalls)
        assertEquals(0, f.remote.deleteCalls)
        assertEquals(0, f.vault.writes)
    }

    @Test
    fun localEditsOnlyWrite() = runTest {
        val f = Fixture()
        f.syncedVault()
        for (i in 1..K) f.vault.write("notes/n$i.md", bufferOf("note $i, edited locally"))
        f.reset()

        f.engine.syncNow()

        assertEquals(K, f.remote.writeCalls)
        assertEquals(0, f.remote.readCalls)
        assertEquals(0, f.remote.statCalls)
        assertEquals(1, f.remote.listAllCalls)
    }

    @Test
    fun remoteEditsReadOnce() = runTest {
        val f = Fixture()
        f.syncedVault()
        for (i in 1..K) f.inner.write(RemotePath("notes/n$i.md"), bufferOf("note $i, edited remotely"))

        f.engine.syncNow()

        assertEquals(K, f.remote.readCalls)
        assertEquals(0, f.remote.writeCalls)
        assertEquals(0, f.remote.statCalls)
        assertEquals(K, f.vault.writes)
        assertEquals("note 1, edited remotely", f.vault.text("notes/n1.md"))
    }

    @Test
    fun lostIndexWithIdenticalFilesReadsEachOnceAndWritesNothing() = runTest {
        val f = Fixture()
        f.syncedVault()
        val freshStore = InMemorySyncStateStore()
        val engine = DefaultSyncEngine(f.vault, f.remote, freshStore)

        engine.syncNow()

        assertEquals(N, f.remote.readCalls)
        assertEquals(0, f.remote.writeCalls)
        assertEquals(0, f.remote.statCalls)
        assertEquals(0, f.vault.writes)
        assertEquals(N, freshStore.load().records.size)
        val p = assertNotNull(engine.progress.value)
        assertEquals(N, p.total)
        assertEquals(N, p.done)
        assertTrue(p.completed.isEmpty(), "matching content is not a completed action: ${p.completed}")
        assertTrue(p.problems.isEmpty())

        // индекс восстановлен: следующий прогон снова ничего не читает
        f.reset()
        engine.syncNow()
        assertEquals(0, f.remote.readCalls)
    }

    @Test
    fun realConflictReadsTwiceWritesOnce() = runTest {
        val f = Fixture()
        f.syncedVault()
        f.vault.write("notes/n1.md", bufferOf("local edit"))
        f.inner.write(RemotePath("notes/n1.md"), bufferOf("remote edit, longer"))
        f.reset()

        f.engine.syncNow()

        assertEquals(2, f.remote.readCalls)
        assertEquals(1, f.remote.writeCalls)
        assertEquals("remote edit, longer", f.vault.text("notes/n1.md"))
        val copy = f.vault.list().single { it.relativePath.startsWith("notes/n1 (conflicted copy") }
        assertEquals("local edit", f.vault.text(copy.relativePath))
        assertEquals(
            "local edit",
            f.inner.read(RemotePath(copy.relativePath)).readByteArray().decodeToString(),
        )
        val p = assertNotNull(f.engine.progress.value)
        assertEquals(listOf(SyncItemAction.Conflict), p.completed.map { it.action })
        assertTrue(p.problems.isEmpty())
        assertEquals(N + 1, f.store.load().records.size)
    }

    @Test
    fun reconcileWhereRemoteEqualsBaseUploads() = runTest {
        val f = Fixture()
        f.syncedVault()
        // удалённый файл перезаписан тем же содержимым (новая ревизия), локальный — правлен
        f.inner.write(RemotePath("notes/n1.md"), bufferOf("note 1"))
        f.vault.write("notes/n1.md", bufferOf("note 1, edited locally"))
        f.reset()

        f.engine.syncNow()

        assertEquals(1, f.remote.readCalls)
        assertEquals(1, f.remote.writeCalls)
        assertEquals(0, f.vault.writes)
        assertEquals(
            "note 1, edited locally",
            f.inner.read(RemotePath("notes/n1.md")).readByteArray().decodeToString(),
        )
        assertEquals(N, f.vault.list().size)
        val p = assertNotNull(f.engine.progress.value)
        assertEquals(listOf(SyncItemAction.Upload), p.completed.map { it.action })

        f.reset()
        f.engine.syncNow()
        assertEquals(0, f.remote.readCalls)
        assertEquals(0, f.remote.writeCalls)
    }

    private companion object {
        const val N = 50
        const val K = 3
    }
}
