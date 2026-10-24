package dev.mimir.core

import dev.mimir.core.rules.SyncRules
import dev.mimir.core.sync.FileSyncRecord
import dev.mimir.core.sync.SyncAction
import dev.mimir.core.sync.SyncIndex
import dev.mimir.core.sync.SyncPlan
import dev.mimir.core.sync.SyncPlanBuilder
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Таблица решений §3 спеки sync-io построчно. План получает только локальный вычислитель хэша —
 * удалённое содержимое на этапе плана недоступно by construction.
 */
class SyncPlanBuilderTest {

    private val t0 = Instant.parse("2026-10-23T20:00:00Z")
    private val t1 = Instant.parse("2026-10-23T21:00:00Z")
    private val t2 = Instant.parse("2026-10-23T22:00:00Z")

    private val builder = SyncPlanBuilder()

    /** Локальный вычислитель хэша, запоминающий, для каких путей его вызывали. */
    private class LocalHashSpy(private val hash: String = "hash-base") {
        val calls = mutableListOf<String>()
        val fn: suspend (LocalEntry) -> String = { entry ->
            calls += entry.relativePath
            hash
        }
    }

    private fun local(path: String, size: Long = 100L, mtime: Instant = t0) =
        LocalEntry(VaultFileId("local-$path"), path, size, mtime)

    private fun remote(path: String, size: Long = 100L, mtime: Instant = t0, rev: String? = "rev-0") =
        RemoteEntry(RemotePath(path), size, mtime, rev, isDirectory = false)

    private fun base(
        path: String,
        hash: String = "hash-base",
        size: Long = 100L,
        mtime: Instant = t0,
        rev: String? = "rev-0",
    ) = FileSyncRecord(
        relativePath = path,
        localId = VaultFileId("local-$path"),
        baseHash = hash,
        localSize = size,
        localModifiedAt = mtime,
        remotePath = RemotePath(path),
        remoteRevision = rev,
        remoteModifiedAt = mtime,
        remoteSize = size,
    )

    private suspend fun plan(
        local: List<LocalEntry>,
        remote: List<RemoteEntry>,
        index: SyncIndex = SyncIndex(),
        spy: LocalHashSpy = LocalHashSpy(),
    ): SyncPlan = builder.buildPlan(local, remote, index, spy.fn)

    // L есть, R нет, B нет → Upload
    @Test
    fun newLocalFileUploadsWithoutHashing() = runTest {
        val spy = LocalHashSpy()
        val plan = plan(listOf(local("new.md")), emptyList(), spy = spy)

        val action = assertIs<SyncAction.Upload>(plan.actions.single())
        assertEquals("new.md", action.path)
        assertTrue(spy.calls.isEmpty())
    }

    // L нет, R есть, B нет → Download
    @Test
    fun newRemoteFileDownloadsWithoutHashing() = runTest {
        val spy = LocalHashSpy()
        val plan = plan(emptyList(), listOf(remote("remote.md")), spy = spy)

        val action = assertIs<SyncAction.Download>(plan.actions.single())
        assertEquals("remote.md", action.path)
        assertTrue(spy.calls.isEmpty())
    }

    // L есть, R есть, B нет → Reconcile(base = null)
    @Test
    fun bothExistWithoutRecordReconciles() = runTest {
        val spy = LocalHashSpy()
        val plan = plan(listOf(local("note.md")), listOf(remote("note.md")), spy = spy)

        val action = assertIs<SyncAction.Reconcile>(plan.actions.single())
        assertEquals("note.md", action.path)
        assertEquals(local("note.md"), action.localEntry)
        assertEquals(remote("note.md"), action.remoteEntry)
        assertNull(action.baseRecord)
        assertTrue(plan.hasChanges)
    }

    // ничего не изменилось → NoOp
    @Test
    fun unchangedIsNoOpWithoutHashing() = runTest {
        val spy = LocalHashSpy()
        val plan = plan(
            listOf(local("note.md")),
            listOf(remote("note.md")),
            SyncIndex().withRecord(base("note.md")),
            spy,
        )

        assertIs<SyncAction.NoOp>(plan.actions.single())
        assertFalse(plan.hasChanges)
        assertTrue(spy.calls.isEmpty())
    }

    // сменился только mtime удалённого при той же ревизии → UpdateMetadata без чтений
    @Test
    fun onlyRemoteMtimeChangedWithSameRevisionUpdatesMetadata() = runTest {
        val spy = LocalHashSpy()
        val rec = base("note.md")
        val plan = plan(
            listOf(local("note.md")),
            listOf(remote("note.md", mtime = t1)),
            SyncIndex().withRecord(rec),
            spy,
        )

        val action = assertIs<SyncAction.UpdateMetadata>(plan.actions.single())
        assertEquals(rec.copy(remoteModifiedAt = t1), action.record)
        assertFalse(plan.hasChanges)
        assertTrue(spy.calls.isEmpty())
    }

    // адаптер без ревизий: изменение mtime = изменение удалённого
    @Test
    fun withoutRevisionsRemoteMtimeChangeDownloads() = runTest {
        val plan = plan(
            listOf(local("note.md")),
            listOf(remote("note.md", mtime = t1, rev = null)),
            SyncIndex().withRecord(base("note.md", rev = null)),
        )

        assertIs<SyncAction.Download>(plan.actions.single())
    }

    // ревизия есть у одной стороны → сравнение по size+mtime
    @Test
    fun revisionOnOneSideOnlyFallsBackToSizeAndMtime() = runTest {
        val plan = plan(
            listOf(local("note.md")),
            listOf(remote("note.md", rev = "rev-new")),
            SyncIndex().withRecord(base("note.md", rev = null)),
        )

        assertIs<SyncAction.NoOp>(plan.actions.single())
    }

    // новая ревизия при тех же size и mtime — удалённый изменён
    @Test
    fun newRevisionWithSameSizeAndMtimeIsRemoteChange() = runTest {
        val plan = plan(
            listOf(local("note.md")),
            listOf(remote("note.md", rev = "rev-1")),
            SyncIndex().withRecord(base("note.md")),
        )

        assertIs<SyncAction.Download>(plan.actions.single())
    }

    // изменён только L, h(L) == B.hash → UpdateMetadata
    @Test
    fun localOnlyChangedWithSameHashUpdatesMetadata() = runTest {
        val spy = LocalHashSpy("hash-base")
        val rec = base("note.md")
        val plan = plan(
            listOf(local("note.md", mtime = t1)),
            listOf(remote("note.md")),
            SyncIndex().withRecord(rec),
            spy,
        )

        val action = assertIs<SyncAction.UpdateMetadata>(plan.actions.single())
        assertEquals(rec.copy(localModifiedAt = t1), action.record)
        assertEquals(listOf("note.md"), spy.calls)
    }

    // изменён только L, h(L) != B.hash → Upload
    @Test
    fun localOnlyChangedWithNewHashUploads() = runTest {
        val plan = plan(
            listOf(local("note.md", size = 200L, mtime = t1)),
            listOf(remote("note.md")),
            SyncIndex().withRecord(base("note.md")),
            LocalHashSpy("hash-local-new"),
        )

        val action = assertIs<SyncAction.Upload>(plan.actions.single())
        assertEquals(RemotePath("note.md"), action.remotePath)
        assertTrue(plan.hasChanges)
    }

    // изменён только R → Download без хэширования
    @Test
    fun remoteOnlyChangedDownloadsWithoutHashing() = runTest {
        val spy = LocalHashSpy()
        val plan = plan(
            listOf(local("note.md")),
            listOf(remote("note.md", size = 250L, mtime = t1, rev = "rev-1")),
            SyncIndex().withRecord(base("note.md")),
            spy,
        )

        assertIs<SyncAction.Download>(plan.actions.single())
        assertTrue(spy.calls.isEmpty())
    }

    // изменены оба, h(L) == B.hash → Download
    @Test
    fun bothChangedLocalEqualsBaseDownloads() = runTest {
        val spy = LocalHashSpy("hash-base")
        val plan = plan(
            listOf(local("note.md", mtime = t1)),
            listOf(remote("note.md", size = 200L, mtime = t2, rev = "rev-2")),
            SyncIndex().withRecord(base("note.md")),
            spy,
        )

        assertIs<SyncAction.Download>(plan.actions.single())
        assertEquals(listOf("note.md"), spy.calls)
    }

    // изменены оба, h(L) != B.hash → Reconcile(base = B)
    @Test
    fun bothChangedLocalDiffersReconciles() = runTest {
        val rec = base("note.md")
        val plan = plan(
            listOf(local("note.md", size = 150L, mtime = t1)),
            listOf(remote("note.md", size = 200L, mtime = t2, rev = "rev-2")),
            SyncIndex().withRecord(rec),
            LocalHashSpy("hash-local"),
        )

        val action = assertIs<SyncAction.Reconcile>(plan.actions.single())
        assertSame(rec, action.baseRecord)
        assertEquals(local("note.md", size = 150L, mtime = t1), action.localEntry)
        assertEquals(remote("note.md", size = 200L, mtime = t2, rev = "rev-2"), action.remoteEntry)
    }

    // L нет, R изменён → Download (воскрешение)
    @Test
    fun localDeletedRemoteChangedDownloads() = runTest {
        val spy = LocalHashSpy()
        val plan = plan(
            emptyList(),
            listOf(remote("note.md", size = 300L, mtime = t1, rev = "rev-modified")),
            SyncIndex().withRecord(base("note.md")),
            spy,
        )

        assertIs<SyncAction.Download>(plan.actions.single())
        assertTrue(spy.calls.isEmpty())
    }

    // та же ревизия, но другой размер — удалённый изменён
    @Test
    fun sameRevisionWithDifferentSizeIsRemoteChange() = runTest {
        val spy = LocalHashSpy()
        val plan = plan(
            listOf(local("note.md")),
            listOf(remote("note.md", size = 101L)),
            SyncIndex().withRecord(base("note.md")),
            spy,
        )

        assertIs<SyncAction.Download>(plan.actions.single())
        assertTrue(spy.calls.isEmpty())
    }

    // L нет, та же ревизия, сменился только mtime удалённого → R не изменён → DeleteRemote
    @Test
    fun localDeletedRemoteMtimeOnlyChangedDeletesRemote() = runTest {
        val plan = plan(
            emptyList(),
            listOf(remote("note.md", mtime = t1)),
            SyncIndex().withRecord(base("note.md")),
        )

        assertIs<SyncAction.DeleteRemote>(plan.actions.single())
    }

    // L нет, R не изменён → DeleteRemote
    @Test
    fun localDeletedRemoteUnchangedDeletesRemote() = runTest {
        val plan = plan(
            emptyList(),
            listOf(remote("note.md")),
            SyncIndex().withRecord(base("note.md")),
        )

        val action = assertIs<SyncAction.DeleteRemote>(plan.actions.single())
        assertEquals(RemotePath("note.md"), action.remotePath)
    }

    // R нет, L изменён и h(L) != B.hash → Upload
    @Test
    fun remoteDeletedLocalChangedWithNewHashUploads() = runTest {
        val plan = plan(
            listOf(local("note.md", size = 350L, mtime = t1)),
            emptyList(),
            SyncIndex().withRecord(base("note.md")),
            LocalHashSpy("hash-local-mod"),
        )

        assertIs<SyncAction.Upload>(plan.actions.single())
    }

    // R нет, L изменён только по mtime (хэш тот же) → DeleteLocal
    @Test
    fun remoteDeletedLocalTouchedWithSameHashDeletesLocal() = runTest {
        val plan = plan(
            listOf(local("note.md", mtime = t1)),
            emptyList(),
            SyncIndex().withRecord(base("note.md")),
            LocalHashSpy("hash-base"),
        )

        assertIs<SyncAction.DeleteLocal>(plan.actions.single())
    }

    // R нет, L не изменён → DeleteLocal без хэширования
    @Test
    fun remoteDeletedLocalUnchangedDeletesLocalWithoutHashing() = runTest {
        val spy = LocalHashSpy()
        val plan = plan(
            listOf(local("note.md")),
            emptyList(),
            SyncIndex().withRecord(base("note.md")),
            spy,
        )

        val action = assertIs<SyncAction.DeleteLocal>(plan.actions.single())
        assertEquals(VaultFileId("local-note.md"), action.localId)
        assertTrue(spy.calls.isEmpty())
    }

    @Test
    fun localHashFailureIsReportedAndSkipped() = runTest {
        val failures = mutableListOf<Pair<String, Throwable>>()
        val plan = builder.buildPlan(
            local = listOf(local("a.md", mtime = t1), local("b.md")),
            remote = listOf(remote("a.md"), remote("b.md")),
            index = SyncIndex().withRecord(base("a.md")).withRecord(base("b.md")),
            localHash = { throw IOException("locked") },
            onReconcileFailure = { path, error -> failures += path to error },
        )

        assertEquals(listOf("a.md"), failures.map { it.first })
        assertIs<IOException>(failures.single().second)
        assertTrue(plan.actions.all { it is SyncAction.NoOp })
    }

    @Test
    fun localHashFailureWithoutCallbackPropagates() = runTest {
        assertFailsWith<IOException> {
            builder.buildPlan(
                local = listOf(local("a.md", mtime = t1)),
                remote = listOf(remote("a.md")),
                index = SyncIndex().withRecord(base("a.md")),
                localHash = { throw IOException("locked") },
            )
        }
    }

    @Test
    fun excludedFilesAreIgnoredInPlan() = runTest {
        val customBuilder = SyncPlanBuilder(
            rules = SyncRules(excludedGlobs = listOf(".obsidian/workspace*.json", ".trash/**")),
        )

        val plan = customBuilder.buildPlan(
            local = listOf(
                local(".obsidian/workspace.json"),
                local(".trash/old.md"),
                local("valid.md"),
            ),
            remote = listOf(
                remote(".obsidian/workspace-mobile.json"),
                remote(".trash/sub/other.md"),
            ),
            index = SyncIndex(),
            localHash = { "hash" },
        )

        assertEquals("valid.md", plan.actions.single().path)
        assertIs<SyncAction.Upload>(plan.actions.single())
    }

    @Test
    fun remoteFilesUnderBuiltinExcludesAreNeitherDownloadedNorDeleted() = runTest {
        val paths = listOf(
            ".obsidian/plugins/p/node_modules/q/index.js",
            "node_modules/pkg/index.js",
            "sub/repo/.git/HEAD",
            ".mimir_canary",
            "note.tmp",
        )
        val plan = plan(emptyList(), paths.map { remote(it) })

        assertTrue(plan.actions.isEmpty(), "unexpected actions: ${plan.actions}")
    }

    @Test
    fun indexedFileUnderBuiltinExcludesIsNotDeletedFromRemote() = runTest {
        val path = "node_modules/pkg/index.js"
        val plan = plan(emptyList(), listOf(remote(path)), SyncIndex().withRecord(base(path)))

        assertTrue(plan.actions.isEmpty(), "unexpected actions: ${plan.actions}")
    }
}
