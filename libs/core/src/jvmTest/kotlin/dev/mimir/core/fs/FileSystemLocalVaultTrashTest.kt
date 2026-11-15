package dev.mimir.core.fs

import dev.mimir.core.testing.TestClock
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class FileSystemLocalVaultTrashTest {
    private val root = Files.createTempDirectory("mimir-vault-trash-test")
    private val clock = TestClock(Instant.parse("2026-11-15T12:00:00Z"))
    private val vault = FileSystemLocalVault(Path(root.toString()), clock = clock)

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    private fun String.source() = Buffer().also { it.write(encodeToByteArray()) }

    private fun exists(relative: String): Boolean = root.resolve(relative).exists()

    @Test
    fun trashMovesFileUnderDatedFolderAndHidesIt() = runTest {
        vault.write("notes/a.md", "x".source())
        vault.trash(vault.getEntry("notes/a.md")!!.id)

        assertTrue(exists(".mimir/trash/2026-11-15/notes/a.md"))
        assertEquals("x", root.resolve(".mimir/trash/2026-11-15/notes/a.md").readText())
        assertNull(vault.getEntry("notes/a.md"))
        assertTrue(vault.list().none { it.relativePath.startsWith(".mimir") })
    }

    @Test
    fun secondTrashOfSamePathSameDayGetsSuffix() = runTest {
        vault.write("notes/a.md", "first".source())
        vault.trash(vault.getEntry("notes/a.md")!!.id)

        vault.write("notes/a.md", "second".source())
        vault.trash(vault.getEntry("notes/a.md")!!.id)

        assertTrue(exists(".mimir/trash/2026-11-15/notes/a.md"))
        assertTrue(exists(".mimir/trash/2026-11-15/notes/a (2).md"))
        assertEquals("first", root.resolve(".mimir/trash/2026-11-15/notes/a.md").readText())
        assertEquals("second", root.resolve(".mimir/trash/2026-11-15/notes/a (2).md").readText())
    }

    @Test
    fun purgeRemovesOnlyOldDates() = runTest {
        val oldFile = root.resolve(".mimir/trash/2026-10-01/notes/old.md")
        oldFile.parent.createDirectories()
        oldFile.writeText("old")

        val recentFile = root.resolve(".mimir/trash/2026-11-14/notes/recent.md")
        recentFile.parent.createDirectories()
        recentFile.writeText("recent")

        vault.purgeTrash(Instant.parse("2026-10-16T00:00:00Z"))

        assertFalse(exists(".mimir/trash/2026-10-01"))
        assertTrue(exists(".mimir/trash/2026-11-14/notes/recent.md"))
    }
}
