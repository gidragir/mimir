package dev.mimir.core

import dev.mimir.core.sync.ConflictResolver
import dev.mimir.core.sync.DefaultConflictResolver
import dev.mimir.core.sync.DefaultSyncEngine
import dev.mimir.core.sync.InMemorySyncStateStore
import dev.mimir.core.testing.InMemoryLocalVault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class SyncEngineProgressTest {

    private fun bufferOf(text: String): Buffer =
        Buffer().also { it.write(text.encodeToByteArray()) }

    private class Fixture(
        val vault: InMemoryLocalVault = InMemoryLocalVault(),
        val remote: InMemoryRemoteStorage = InMemoryRemoteStorage(),
        val store: InMemorySyncStateStore = InMemorySyncStateStore(),
        remoteStorage: RemoteStorage = remote,
        conflictResolver: ConflictResolver = DefaultConflictResolver(),
    ) {
        val engine = DefaultSyncEngine(vault, remoteStorage, store, conflictResolver)
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
    fun scanningPublishesFolderCountEvenWhenOnlySubfoldersAreFound() = runTest {
        val f = Fixture()
        f.remote.write(RemotePath("a/b/c/deep.md"), bufferOf("1"))
        f.remote.write(RemotePath("d/e/other.md"), bufferOf("2"))

        val scanning = mutableListOf<SyncProgress>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.engine.progress.collect { p ->
                if (p != null && p.phase == SyncPhase.Scanning) scanning += p
            }
        }

        f.engine.syncNow()

        // root, a, a/b, a/b/c, d, d/e
        val beforeAnyFile = scanning.filter { it.scanned == 0 }.map { it.foldersScanned }.distinct()
        assertTrue(listOf(1, 2, 3, 4).all { it in beforeAnyFile }, "folders-only steps must be published: $beforeAnyFile")
        assertEquals(scanning.map { it.foldersScanned }.sorted(), scanning.map { it.foldersScanned })
        assertEquals(6, scanning.last().foldersScanned)
        assertEquals(2, scanning.last().scanned)
        assertEquals(6, assertNotNull(f.engine.progress.value).foldersScanned)
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

    @Test
    fun cancelledSyncIsNotReportedAsError() = runTest {
        val raw = InMemoryRemoteStorage()
        val writeStarted = CompletableDeferred<Unit>()
        val hanging = object : RemoteStorage by raw {
            override suspend fun write(path: RemotePath, data: Source): RemoteEntry {
                writeStarted.complete(Unit)
                awaitCancellation()
            }
        }
        val f = Fixture(remote = raw, remoteStorage = hanging)
        f.vault.write("a.md", bufferOf("A"))

        val job = launch { f.engine.syncNow() }
        writeStarted.await()
        assertEquals(SyncPhase.Applying, f.engine.progress.value?.phase)
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals(SyncStatus.Idle, f.engine.status.value)
        val p = assertNotNull(f.engine.progress.value)
        assertEquals(SyncPhase.Finished, p.phase)
        assertNotNull(p.finishedAt)
    }

    @Test
    fun failedConflictCopyWriteDoesNotLoseLocalEdit() = runTest {
        val now = Instant.parse("2026-10-24T12:00:00Z")
        val fixedClock = object : Clock {
            override fun now(): Instant = now
        }
        val f = Fixture(conflictResolver = DefaultConflictResolver(fixedClock))
        f.vault.write("a.md", bufferOf("base"))
        f.engine.syncNow()

        f.vault.write("a.md", bufferOf("local edit"))
        f.remote.write(RemotePath("a.md"), bufferOf("remote edit"))
        val copyPath = ConflictResolver.generateConflictPath("a.md", now)
        f.vault.failWriteFor += copyPath

        f.engine.syncNow()

        val local = assertNotNull(f.vault.getEntry("a.md"))
        assertContentEqualsText("local edit", f.vault.read(local.id))
        val problem = assertNotNull(f.engine.progress.value).problems.single()
        assertEquals("a.md", problem.path)
        assertEquals(SyncProblemKind.WriteFailed, problem.kind)

        // следующий прогон не должен принять локальную правку за уже синхронизированную
        f.engine.syncNow()

        val again = assertNotNull(f.vault.getEntry("a.md"))
        assertContentEqualsText("local edit", f.vault.read(again.id))
        assertEquals(1, assertNotNull(f.engine.progress.value).problems.size)

        f.vault.failWriteFor -= copyPath
        f.engine.syncNow()

        val copy = assertNotNull(f.vault.getEntry(copyPath))
        assertContentEqualsText("local edit", f.vault.read(copy.id))
        val main = assertNotNull(f.vault.getEntry("a.md"))
        assertContentEqualsText("remote edit", f.vault.read(main.id))
        assertTrue(assertNotNull(f.engine.progress.value).problems.isEmpty())
    }

    @Test
    fun scanningUsesBulkListAllAndPublishesItsProgress() = runTest {
        val raw = InMemoryRemoteStorage()
        raw.write(RemotePath("a/b/deep.md"), bufferOf("1"))
        raw.write(RemotePath("top.md"), bufferOf("2"))
        val bulk = object : RemoteStorage by raw {
            override suspend fun list(dir: RemotePath): List<RemoteEntry> = error("engine must not recurse via list")
            override suspend fun listAll(root: RemotePath, onProgress: (Int, Int) -> Unit): List<RemoteEntry> {
                val files = raw.listAll(root)
                onProgress(7, 1)
                onProgress(9, files.size)
                return files
            }
        }
        val f = Fixture(remote = raw, remoteStorage = bulk)
        f.vault.write("local.md", bufferOf("L"))

        val scanning = mutableListOf<SyncProgress>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.engine.progress.collect { p ->
                if (p != null && p.phase == SyncPhase.Scanning) scanning += p
            }
        }

        f.engine.syncNow()

        assertTrue(scanning.any { it.foldersScanned == 7 && it.scanned == 2 }, "$scanning")
        assertEquals(9, scanning.last().foldersScanned)
        assertEquals(3, scanning.last().scanned)
        assertNotNull(f.vault.getEntry("a/b/deep.md"))
        assertNotNull(f.vault.getEntry("top.md"))
    }

    @Test
    fun reconcileShowsVerifyAndCompletesAsConflict() = runTest {
        val f = Fixture()
        f.vault.write("a.md", bufferOf("base"))
        f.engine.syncNow()
        f.vault.write("a.md", bufferOf("local edit"))
        f.remote.write(RemotePath("a.md"), bufferOf("remote edit, longer"))

        val seen = mutableListOf<SyncProgress>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.engine.progress.collect { p -> if (p != null) seen += p }
        }

        f.engine.syncNow()

        val applying = seen.first { it.phase == SyncPhase.Applying }
        assertEquals(listOf(SyncItem("a.md", SyncItemAction.Verify)), applying.upcoming)
        assertTrue(seen.any { it.current == SyncItem("a.md", SyncItemAction.Verify) })
        val p = assertNotNull(f.engine.progress.value)
        assertEquals(1, p.total)
        assertEquals(1, p.done)
        assertEquals(listOf("a.md" to SyncItemAction.Conflict), p.completed.map { it.path to it.action })
        assertTrue(p.problems.isEmpty())
    }

    @Test
    fun matchingReconcileIsDoneButNotCompleted() = runTest {
        val f = Fixture()
        f.vault.write("same.md", bufferOf("identical"))
        f.remote.write(RemotePath("same.md"), bufferOf("identical"))

        f.engine.syncNow()

        val p = assertNotNull(f.engine.progress.value)
        assertEquals(1, p.total)
        assertEquals(1, p.done)
        assertTrue(p.completed.isEmpty())
        assertTrue(p.problems.isEmpty())
        val record = assertNotNull(f.store.load().find("same.md"))
        assertEquals(Sha256.digest("identical".encodeToByteArray()).toHex(), record.baseHash)
        assertEquals(f.remote.stat(RemotePath("same.md"))?.revision, record.remoteRevision)
    }

    @Test
    fun localReadFailureWhileStreamingUploadIsReadProblem() = runTest {
        val inner = InMemoryLocalVault()
        val vault = object : LocalVault by inner {
            override suspend fun read(id: VaultFileId): Source = failingSource(IOException("disk went away"))
        }
        val remote = InMemoryRemoteStorage()
        val store = InMemorySyncStateStore()
        val engine = DefaultSyncEngine(vault, remote, store)
        inner.write("a.md", bufferOf("A"))

        engine.syncNow()

        val problem = assertNotNull(engine.progress.value).problems.single()
        assertEquals("a.md", problem.path)
        assertEquals(SyncItemAction.Upload, problem.action)
        assertEquals(SyncProblemKind.ReadFailed, problem.kind)
        assertNull(store.load().find("a.md"))
    }

    @Test
    fun decryptFailureWhileStreamingDownloadIsDecryptProblem() = runTest {
        val raw = InMemoryRemoteStorage()
        raw.write(RemotePath("bad.md"), bufferOf("garbage"))
        val lazilyFailing = object : RemoteStorage by raw {
            override suspend fun read(path: RemotePath): Source = failingSource(CryptException("bad block"))
        }
        val f = Fixture(remote = raw, remoteStorage = lazilyFailing)

        f.engine.syncNow()

        val problem = assertNotNull(f.engine.progress.value).problems.single()
        assertEquals("bad.md", problem.path)
        assertEquals(SyncItemAction.Download, problem.action)
        assertEquals(SyncProblemKind.DecryptFailed, problem.kind)
        assertNull(f.store.load().find("bad.md"))
    }

    /** Источник, который открывается успешно, а падает при первом чтении — как потоковая расшифровка. */
    private fun failingSource(error: Exception): Source = object : RawSource {
        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = throw error
        override fun close() = Unit
    }.buffered()

    private fun assertContentEqualsText(expected: String, source: Source) =
        assertEquals(expected, source.readByteArray().decodeToString())
}
