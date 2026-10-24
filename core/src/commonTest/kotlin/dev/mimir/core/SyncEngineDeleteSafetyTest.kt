package dev.mimir.core

import dev.mimir.core.sync.DefaultSyncEngine
import dev.mimir.core.sync.InMemorySyncStateStore
import dev.mimir.core.testing.InMemoryLocalVault
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Защита от удаления локальных файлов из-за неполного/отстающего списка удалённых файлов. */
class SyncEngineDeleteSafetyTest {

    private fun bufferOf(text: String): Buffer =
        Buffer().also { it.write(text.encodeToByteArray()) }

    /** Обёртка над [InMemoryRemoteStorage]: может «прятать» файлы из списка и ломать [stat]. */
    private class FlakyRemote(val delegate: InMemoryRemoteStorage = InMemoryRemoteStorage()) : RemoteStorage {
        val hiddenFromListing = mutableSetOf<String>()
        var failStatWith: Exception? = null
        var writes = 0
        var deletes = 0
        val statCalls = mutableListOf<String>()

        override suspend fun list(dir: RemotePath): List<RemoteEntry> =
            delegate.list(dir).filterNot { !it.isDirectory && it.path.value in hiddenFromListing }

        override suspend fun stat(path: RemotePath): RemoteEntry? {
            statCalls += path.value
            failStatWith?.let { throw it }
            return delegate.stat(path)
        }

        override suspend fun read(path: RemotePath): Source = delegate.read(path)

        override suspend fun write(path: RemotePath, data: Source): RemoteEntry {
            writes++
            return delegate.write(path, data)
        }

        override suspend fun delete(path: RemotePath) {
            deletes++
            delegate.delete(path)
        }
    }

    private class Fixture {
        val vault = InMemoryLocalVault()
        val remote = FlakyRemote()
        val store = InMemorySyncStateStore()
        val engine = DefaultSyncEngine(vault, remote, store)
    }

    /** Первый прогон: оба файла залиты, в индексе две записи. */
    private suspend fun Fixture.syncTwoFiles() {
        vault.write("a.md", bufferOf("A"))
        vault.write("b.md", bufferOf("B"))
        engine.syncNow()
        assertEquals(setOf("a.md", "b.md"), store.load().records.keys)
    }

    @Test
    fun emptyRemoteWithNonEmptyIndexAbortsWithoutTouchingFiles() = runTest {
        val f = Fixture()
        f.syncTwoFiles()
        f.remote.delegate.delete(RemotePath("a.md"))
        f.remote.delegate.delete(RemotePath("b.md"))
        val indexBefore = f.store.load()
        val writesBefore = f.remote.writes
        val deletesBefore = f.remote.deletes

        val error = assertFailsWith<SyncAbortedException> { f.engine.syncNow() }

        val status = assertIs<SyncStatus.Error>(f.engine.status.value)
        assertEquals(error.message, status.message)
        assertTrue(status.message.contains("пуст"), status.message)
        assertEquals(setOf("a.md", "b.md"), f.vault.list().map { it.relativePath }.toSet())
        assertEquals(indexBefore, f.store.load())
        assertEquals(writesBefore, f.remote.writes)
        assertEquals(deletesBefore, f.remote.deletes)
        assertEquals(SyncPhase.Finished, assertNotNull(f.engine.progress.value).phase)
    }

    @Test
    fun emptyRemoteWithEmptyIndexSyncsNormally() = runTest {
        val f = Fixture()
        f.vault.write("c.md", bufferOf("C"))

        f.engine.syncNow()

        assertEquals(SyncStatus.Idle, f.engine.status.value)
        assertNotNull(f.remote.delegate.stat(RemotePath("c.md")))
        assertEquals(setOf("c.md"), f.store.load().records.keys)
    }

    @Test
    fun emptyRemoteEmptyIndexAndEmptyVaultIsNotAnError() = runTest {
        val f = Fixture()

        f.engine.syncNow()

        assertEquals(SyncStatus.Idle, f.engine.status.value)
    }

    @Test
    fun deleteLocalIsSkippedWhenStatStillSeesTheRemoteFile() = runTest {
        val f = Fixture()
        f.syncTwoFiles()
        val recordBefore = f.store.load().find("a.md")
        f.remote.hiddenFromListing += "a.md" // отстающий индекс поиска: файл есть, но в списке его нет

        f.engine.syncNow()

        assertEquals(SyncStatus.Idle, f.engine.status.value)
        assertNotNull(f.vault.getEntry("a.md"), "local file must be kept")
        assertEquals(recordBefore, f.store.load().find("a.md"), "index record must stay unchanged")
        assertTrue("a.md" in f.remote.statCalls)
        val p = assertNotNull(f.engine.progress.value)
        assertTrue(p.problems.isEmpty(), "skip is not a user-visible failure: ${p.problems}")
        assertEquals(p.total, p.done)
        assertTrue(p.completed.none { it.path == "a.md" })
    }

    @Test
    fun deleteLocalProceedsWhenStatConfirmsRemoteDeletion() = runTest {
        val f = Fixture()
        f.syncTwoFiles()
        f.remote.delegate.delete(RemotePath("a.md"))

        f.engine.syncNow()

        assertEquals(SyncStatus.Idle, f.engine.status.value)
        assertNull(f.vault.getEntry("a.md"))
        assertNotNull(f.vault.getEntry("b.md"))
        assertNull(f.store.load().find("a.md"))
        assertTrue("a.md" in f.remote.statCalls)
    }

    @Test
    fun deleteLocalIsSkippedWithProblemWhenVerificationFails() = runTest {
        val f = Fixture()
        f.syncTwoFiles()
        val recordBefore = f.store.load().find("a.md")
        f.remote.delegate.delete(RemotePath("a.md"))
        f.remote.failStatWith = IllegalStateException("boom")

        f.engine.syncNow()

        assertNotNull(f.vault.getEntry("a.md"), "never delete when verification failed")
        assertEquals(recordBefore, f.store.load().find("a.md"))
        val problem = assertNotNull(f.engine.progress.value).problems.single()
        assertEquals("a.md", problem.path)
        assertEquals(SyncItemAction.DeleteLocal, problem.action)
        assertEquals(SyncProblemKind.Unknown, problem.kind)
        assertTrue(problem.detail.contains("boom"), problem.detail)
    }

    @Test
    fun remoteUnavailableFromStatAbortsTheRun() = runTest {
        val f = Fixture()
        f.syncTwoFiles()
        f.remote.delegate.delete(RemotePath("a.md"))
        f.remote.failStatWith = RemoteUnavailableException("offline")

        assertFailsWith<RemoteUnavailableException> { f.engine.syncNow() }

        assertEquals(SyncStatus.Offline, f.engine.status.value)
        assertNotNull(f.vault.getEntry("a.md"))
        assertNotNull(f.store.load().find("a.md"))
    }
}
