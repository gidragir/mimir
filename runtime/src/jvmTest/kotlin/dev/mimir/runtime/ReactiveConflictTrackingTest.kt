package dev.mimir.runtime

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReactiveConflictTrackingTest {
    private val configDir = Files.createTempDirectory("mimir-conflict-cfg")
    private val vaultDir = Files.createTempDirectory("mimir-conflict-vault")
    private var runtime: MimirRuntime? = null

    @AfterTest
    fun cleanup() {
        runtime?.close()
        configDir.toFile().deleteRecursively()
        vaultDir.toFile().deleteRecursively()
    }

    @Test
    fun testConflictListResolvesViaStreamAndUpdatesConflicts() = runTest {
        val rt = testRuntime(configDir, vaultPath = vaultDir.toString()).also { runtime = it }
        rt.initialize()

        val origFile = vaultDir.resolve("Notes/Doc.md")
        origFile.parent.createDirectories()
        origFile.writeText("Original Content")

        val conflictFile = vaultDir.resolve("Notes/Doc (conflicted copy 2026-10-24-120000).md")
        conflictFile.writeText("Conflict Resolved Content")

        val initialConflicts = rt.conflicts()
        assertEquals(1, initialConflicts.size)
        assertEquals("Notes/Doc.md", initialConflicts[0].originalPath)

        // Resolve conflict keeping conflict copy
        rt.resolveConflict("Notes/Doc.md", "Notes/Doc (conflicted copy 2026-10-24-120000).md", keepConflict = true)

        assertEquals("Conflict Resolved Content", Files.readString(origFile))
        assertFalse(Files.exists(conflictFile))
        assertEquals(0, rt.conflictsFlow.value.size)
    }

    @Test
    fun conflictAppearsInFlowFromFileEventWithoutAnyExplicitRefresh() = kotlinx.coroutines.runBlocking {
        val rt = testRuntime(configDir, vaultPath = vaultDir.toString()).also { runtime = it }
        rt.initialize()
        kotlinx.coroutines.delay(500) // регистрация watcher'а
        assertTrue(rt.conflictsFlow.value.isEmpty())

        vaultDir.resolve("Doc (conflicted copy 2026-10-24-120000).md").writeText("x")

        val found = kotlinx.coroutines.withTimeout(10_000) { rt.conflictsFlow.first { it.isNotEmpty() } }
        assertEquals("Doc.md", found.single().originalPath)
    }
}
