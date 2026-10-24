package dev.mimir.core

import dev.mimir.core.sync.DefaultSyncEngine
import dev.mimir.core.sync.InMemorySyncStateStore
import dev.mimir.core.testing.InMemoryLocalVault
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncEngineProgressTest {

    private fun bufferOf(text: String): Buffer =
        Buffer().also { it.write(text.encodeToByteArray()) }

    private class Fixture(
        val vault: InMemoryLocalVault = InMemoryLocalVault(),
        val remote: InMemoryRemoteStorage = InMemoryRemoteStorage(),
        val store: InMemorySyncStateStore = InMemorySyncStateStore(),
        remoteStorage: RemoteStorage = remote,
    ) {
        val engine = DefaultSyncEngine(vault, remoteStorage, store)
    }

    @Test
    fun progressCountsAllPlannedActions() = runTest {
        val f = Fixture()
        f.vault.write("a.md", bufferOf("A"))
        f.vault.write("b.md", bufferOf("BB"))
        f.vault.write("c.md", bufferOf("CCC"))

        f.engine.syncNow()

        val p = assertNotNull(f.engine.progress.value)
        assertEquals(SyncPhase.Finished, p.phase)
        assertNotNull(p.finishedAt)
        assertEquals(3, p.total)
        assertEquals(3, p.done)
        assertEquals(3, p.completed.size)
        assertTrue(p.problems.isEmpty())
        assertEquals(6L, p.bytesTotal)
        assertEquals(6L, p.bytesDone)
        assertNull(p.current)
        assertTrue(p.completed.all { it.action == SyncItemAction.Upload })
    }

    @Test
    fun upcomingIsCappedAtFive() = runTest {
        val f = Fixture()
        for (i in 1..8) f.vault.write("n$i.md", bufferOf("x$i"))

        var firstWithCurrent: SyncProgress? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.engine.progress.collect { p ->
                if (firstWithCurrent == null && p?.current != null) firstWithCurrent = p
            }
        }

        f.engine.syncNow()

        val snapshot = assertNotNull(firstWithCurrent)
        assertEquals(SyncPhase.Applying, snapshot.phase)
        assertEquals(5, snapshot.upcoming.size)
        assertEquals(8, snapshot.total)
        assertNotNull(snapshot.applyingStartedAt)
    }

    @Test
    fun completedIsCappedAt200() = runTest {
        val f = Fixture()
        for (i in 1..205) f.vault.write("f$i.md", bufferOf("$i"))

        f.engine.syncNow()

        val p = assertNotNull(f.engine.progress.value)
        assertEquals(200, p.completed.size)
        assertEquals(205, p.done)
        assertEquals(205, p.total)
    }

    @Test
    fun failingFileBecomesProblemAndOthersSync() = runTest {
        val f = Fixture()
        f.vault.write("a.md", bufferOf("A"))
        f.vault.write("b.md", bufferOf("B"))
        f.vault.write("c.md", bufferOf("C"))
        f.vault.failReadFor += "b.md"

        f.engine.syncNow()

        assertEquals(SyncStatus.Idle, f.engine.status.value)
        val p = assertNotNull(f.engine.progress.value)
        assertEquals(1, p.problems.size)
        val problem = p.problems.single()
        assertEquals("b.md", problem.path)
        assertEquals(SyncItemAction.Upload, problem.action)
        assertEquals(SyncProblemKind.ReadFailed, problem.kind)
        assertTrue(problem.detail.isNotBlank())
        assertNotNull(f.remote.stat(RemotePath("a.md")))
        assertNotNull(f.remote.stat(RemotePath("c.md")))
        assertNull(f.remote.stat(RemotePath("b.md")))
        assertNull(f.store.load().find("b.md"))
    }

    @Test
    fun failedFileIsRetriedNextRun() = runTest {
        val f = Fixture()
        f.vault.write("a.md", bufferOf("A"))
        f.vault.write("b.md", bufferOf("B"))
        f.vault.failReadFor += "b.md"
        f.engine.syncNow()
        assertNull(f.remote.stat(RemotePath("b.md")))

        f.vault.failReadFor -= "b.md"
        f.engine.syncNow()

        assertNotNull(f.remote.stat(RemotePath("b.md")))
        assertNotNull(f.store.load().find("b.md"))
        val p = assertNotNull(f.engine.progress.value)
        assertTrue(p.problems.isEmpty())
        assertEquals(SyncStatus.Idle, f.engine.status.value)
    }

    @Test
    fun comparingFailureForOneFileIsProblem() = runTest {
        val f = Fixture()
        f.vault.write("a.md", bufferOf("base"))
        f.engine.syncNow()

        // обе стороны изменили a.md → при сравнении нужен хэш локального файла
        f.vault.write("a.md", bufferOf("local edit"))
        f.remote.write(RemotePath("a.md"), bufferOf("remote edit, longer"))
        f.vault.write("c.md", bufferOf("C"))
        f.vault.failReadFor += "a.md"

        f.engine.syncNow()

        assertEquals(SyncStatus.Idle, f.engine.status.value)
        val p = assertNotNull(f.engine.progress.value)
        assertEquals(SyncPhase.Finished, p.phase)
        val problem = p.problems.single { it.path == "a.md" }
        assertEquals(SyncProblemKind.ReadFailed, problem.kind)
        assertNotNull(f.remote.stat(RemotePath("c.md")))
        assertNotNull(f.store.load().find("c.md"))
        assertContentEqualsText("remote edit, longer", f.remote.read(RemotePath("a.md")))
    }

    @Test
    fun remoteUnavailableSetsOffline() = runTest {
        val f = Fixture()
        f.vault.write("a.md", bufferOf("A"))
        f.remote.failListWith = RemoteUnavailableException("no net")

        assertFailsWith<RemoteUnavailableException> { f.engine.syncNow() }

        assertEquals(SyncStatus.Offline, f.engine.status.value)
        val p = assertNotNull(f.engine.progress.value)
        assertEquals(SyncPhase.Finished, p.phase)
        assertNotNull(p.finishedAt)
    }

    @Test
    fun remoteAuthFailureSetsError() = runTest {
        val f = Fixture()
        f.remote.failListWith = RemoteAuthException("revoked")

        assertFailsWith<RemoteAuthException> { f.engine.syncNow() }

        assertTrue(f.engine.status.value is SyncStatus.Error)
    }

    @Test
    fun cryptFailureIsDecryptProblem() = runTest {
        val raw = InMemoryRemoteStorage()
        raw.write(RemotePath("bad.md"), bufferOf("garbage"))
        raw.write(RemotePath("good.md"), bufferOf("fine"))
        val failing = object : RemoteStorage by raw {
            override suspend fun read(path: RemotePath): Source {
                if (path.value == "bad.md") throw CryptException("authentication failed")
                return raw.read(path)
            }
        }
        val f = Fixture(remote = raw, remoteStorage = failing)

        f.engine.syncNow()

        assertEquals(SyncStatus.Idle, f.engine.status.value)
        val p = assertNotNull(f.engine.progress.value)
        val problem = p.problems.single()
        assertEquals("bad.md", problem.path)
        assertEquals(SyncItemAction.Download, problem.action)
        assertEquals(SyncProblemKind.DecryptFailed, problem.kind)
        assertEquals("authentication failed", problem.detail)
        assertNotNull(f.vault.getEntry("good.md"))
        assertNull(f.vault.getEntry("bad.md"))
    }

    private fun assertContentEqualsText(expected: String, source: Source) =
        assertEquals(expected, source.readByteArray().decodeToString())
}
