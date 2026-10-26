package dev.mimir.daemon

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
    private var runtime: DaemonRuntime? = null

    @AfterTest
    fun cleanup() {
        runtime?.close()
        configDir.toFile().deleteRecursively()
        vaultDir.toFile().deleteRecursively()
    }

    @Test
    fun testConflictListResolvesViaStreamAndUpdatesConflicts() = runTest {
        val config = DaemonConfig(
            vaultPath = vaultDir.toString(),
            configDir = configDir.toString(),
        )
        val rt = DaemonRuntime(config).also { runtime = it }
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
}
