package dev.mimir.core

import dev.mimir.core.rules.SyncRules
import dev.mimir.core.sync.FileSyncRecord
import dev.mimir.core.sync.SyncAction
import dev.mimir.core.sync.SyncIndex
import dev.mimir.core.sync.SyncPlanBuilder
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class SyncPlanBuilderTest {

    private val t0 = Instant.parse("2026-10-23T20:00:00Z")
    private val t1 = Instant.parse("2026-10-23T21:00:00Z")
    private val t2 = Instant.parse("2026-10-23T22:00:00Z")

    private val builder = SyncPlanBuilder()

    private fun local(path: String, size: Long = 100L, mtime: Instant = t0) =
        LocalEntry(VaultFileId("local-$path"), path, size, mtime)

    private fun remote(path: String, size: Long = 100L, mtime: Instant = t0, rev: String = "rev-0") =
        RemoteEntry(RemotePath(path), size, mtime, rev, isDirectory = false)

    private fun base(path: String, hash: String = "hash-base", size: Long = 100L, mtime: Instant = t0, rev: String = "rev-0") =
        FileSyncRecord(
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

    @Test
    fun newLocalFile_producesUpload() = runTest {
        val plan = builder.buildPlan(
            local = listOf(local("new.md")),
            remote = emptyList(),
            index = SyncIndex(),
            hashCalculator = { _, _ -> "hash-new" },
        )

        assertEquals(1, plan.actions.size)
        val action = plan.actions[0]
        assertTrue(action is SyncAction.Upload)
        assertEquals("new.md", action.path)
    }

    @Test
    fun newRemoteFile_producesDownload() = runTest {
        val plan = builder.buildPlan(
            local = emptyList(),
            remote = listOf(remote("remote.md")),
            index = SyncIndex(),
            hashCalculator = { _, _ -> "hash-remote" },
        )

        assertEquals(1, plan.actions.size)
        val action = plan.actions[0]
        assertTrue(action is SyncAction.Download)
        assertEquals("remote.md", action.path)
    }

    @Test
    fun newBothFiles_identicalHash_producesUpdateMetadata() = runTest {
        val plan = builder.buildPlan(
            local = listOf(local("note.md")),
            remote = listOf(remote("note.md")),
            index = SyncIndex(),
            hashCalculator = { _, _ -> "identical-hash" },
        )

        assertEquals(1, plan.actions.size)
        val action = plan.actions[0]
        assertTrue(action is SyncAction.UpdateMetadata)
        assertEquals("note.md", action.path)
    }

    @Test
    fun newBothFiles_differentHash_producesConflict() = runTest {
        val plan = builder.buildPlan(
            local = listOf(local("note.md")),
            remote = listOf(remote("note.md")),
            index = SyncIndex(),
            hashCalculator = { _, isLocal -> if (isLocal) "local-hash" else "remote-hash" },
        )

        assertEquals(1, plan.actions.size)
        val action = plan.actions[0]
        assertTrue(action is SyncAction.Conflict)
        assertEquals("note.md", action.path)
    }

    @Test
    fun unchanged_producesNoOp() = runTest {
        val rec = base("note.md")
        val plan = builder.buildPlan(
            local = listOf(local("note.md")),
            remote = listOf(remote("note.md")),
            index = SyncIndex().withRecord(rec),
            hashCalculator = { _, _ -> "hash-base" },
        )

        assertEquals(1, plan.actions.size)
        assertTrue(plan.actions[0] is SyncAction.NoOp)
        assertFalse(plan.hasChanges)
    }

    @Test
    fun localModified_remoteUnchanged_producesUpload() = runTest {
        val rec = base("note.md")
        val plan = builder.buildPlan(
            local = listOf(local("note.md", size = 200L, mtime = t1)),
            remote = listOf(remote("note.md")),
            index = SyncIndex().withRecord(rec),
            hashCalculator = { _, isLocal -> if (isLocal) "hash-local-new" else "hash-base" },
        )

        assertEquals(1, plan.actions.size)
        val action = plan.actions[0]
        assertTrue(action is SyncAction.Upload)
        assertEquals("note.md", action.path)
        assertTrue(plan.hasChanges)
    }

    @Test
    fun remoteModified_localUnchanged_producesDownload() = runTest {
        val rec = base("note.md")
        val plan = builder.buildPlan(
            local = listOf(local("note.md")),
            remote = listOf(remote("note.md", size = 250L, mtime = t1, rev = "rev-1")),
            index = SyncIndex().withRecord(rec),
            hashCalculator = { _, isLocal -> if (isLocal) "hash-base" else "hash-remote-new" },
        )

        assertEquals(1, plan.actions.size)
        val action = plan.actions[0]
        assertTrue(action is SyncAction.Download)
        assertEquals("note.md", action.path)
        assertTrue(plan.hasChanges)
    }

    @Test
    fun bothModified_sameHash_producesUpdateMetadata() = runTest {
        val rec = base("note.md")
        val plan = builder.buildPlan(
            local = listOf(local("note.md", size = 150L, mtime = t1)),
            remote = listOf(remote("note.md", size = 150L, mtime = t2, rev = "rev-2")),
            index = SyncIndex().withRecord(rec),
            hashCalculator = { _, _ -> "same-converged-hash" },
        )

        assertEquals(1, plan.actions.size)
        val action = plan.actions[0]
        assertTrue(action is SyncAction.UpdateMetadata)
        assertEquals("note.md", action.path)
    }

    @Test
    fun bothModified_differentHash_producesConflict() = runTest {
        val rec = base("note.md")
        val plan = builder.buildPlan(
            local = listOf(local("note.md", size = 150L, mtime = t1)),
            remote = listOf(remote("note.md", size = 200L, mtime = t2, rev = "rev-2")),
            index = SyncIndex().withRecord(rec),
            hashCalculator = { _, isLocal -> if (isLocal) "hash-local" else "hash-remote" },
        )

        assertEquals(1, plan.actions.size)
        val action = plan.actions[0]
        assertTrue(action is SyncAction.Conflict)
        assertEquals("note.md", action.path)
    }

    @Test
    fun localDeleted_remoteUnchanged_producesDeleteRemote() = runTest {
        val rec = base("note.md")
        val plan = builder.buildPlan(
            local = emptyList(),
            remote = listOf(remote("note.md")),
            index = SyncIndex().withRecord(rec),
            hashCalculator = { _, _ -> "hash-base" },
        )

        assertEquals(1, plan.actions.size)
        val action = plan.actions[0]
        assertTrue(action is SyncAction.DeleteRemote)
        assertEquals("note.md", action.path)
    }

    @Test
    fun remoteDeleted_localUnchanged_producesDeleteLocal() = runTest {
        val rec = base("note.md")
        val plan = builder.buildPlan(
            local = listOf(local("note.md")),
            remote = emptyList(),
            index = SyncIndex().withRecord(rec),
            hashCalculator = { _, _ -> "hash-base" },
        )

        assertEquals(1, plan.actions.size)
        val action = plan.actions[0]
        assertTrue(action is SyncAction.DeleteLocal)
        assertEquals("note.md", action.path)
    }

    @Test
    fun localDeleted_remoteModified_producesDownload() = runTest {
        val rec = base("note.md")
        val plan = builder.buildPlan(
            local = emptyList(),
            remote = listOf(remote("note.md", size = 300L, mtime = t1, rev = "rev-modified")),
            index = SyncIndex().withRecord(rec),
            hashCalculator = { _, _ -> "hash-remote-mod" },
        )

        assertEquals(1, plan.actions.size)
        val action = plan.actions[0]
        assertTrue(action is SyncAction.Download)
        assertEquals("note.md", action.path)
    }

    @Test
    fun remoteDeleted_localModified_producesUpload() = runTest {
        val rec = base("note.md")
        val plan = builder.buildPlan(
            local = listOf(local("note.md", size = 350L, mtime = t1)),
            remote = emptyList(),
            index = SyncIndex().withRecord(rec),
            hashCalculator = { _, _ -> "hash-local-mod" },
        )

        assertEquals(1, plan.actions.size)
        val action = plan.actions[0]
        assertTrue(action is SyncAction.Upload)
        assertEquals("note.md", action.path)
    }

    @Test
    fun excludedFiles_areIgnoredInPlan() = runTest {
        val customRules = SyncRules(
            excludedGlobs = listOf(".obsidian/workspace*.json", ".trash/**")
        )
        val customBuilder = SyncPlanBuilder(rules = customRules)

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
            hashCalculator = { _, _ -> "hash" },
        )

        assertEquals(1, plan.actions.size)
        assertEquals("valid.md", plan.actions[0].path)
        assertTrue(plan.actions[0] is SyncAction.Upload)
    }
}
