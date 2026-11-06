package dev.mimir.desktop.picker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FallbackFolderPickerTest {
    @Test
    fun `returns path from the first candidate that selects a folder`() = runTest {
        var secondCalled = false
        val picker = FallbackFolderPicker(
            FolderPickerCandidate { PickOutcome.Selected("/vault/notes") },
            FolderPickerCandidate { secondCalled = true; PickOutcome.Selected("/fallback") },
        )
        assertEquals("/vault/notes", picker.pickDirectory("/init"))
        assertTrue(!secondCalled)
    }

    @Test
    fun `stops chain and returns null when user cancels dialog`() = runTest {
        var secondCalled = false
        val picker = FallbackFolderPicker(
            FolderPickerCandidate { PickOutcome.Cancelled },
            FolderPickerCandidate { secondCalled = true; PickOutcome.Selected("/fallback") },
        )
        assertNull(picker.pickDirectory(null))
        assertTrue(!secondCalled)
    }

    @Test
    fun `falls back to next candidate on Unavailable or thrown exception`() = runTest {
        val picker = FallbackFolderPicker(
            FolderPickerCandidate { PickOutcome.Unavailable("no portal") },
            FolderPickerCandidate { error("boom") },
            FolderPickerCandidate { PickOutcome.Selected("/from-third") },
        )
        assertEquals("/from-third", picker.pickDirectory(null))
    }

    @Test
    fun `rethrows CancellationException without calling fallback`() = runTest {
        var secondCalled = false
        val picker = FallbackFolderPicker(
            FolderPickerCandidate { throw CancellationException("cancelled") },
            FolderPickerCandidate { secondCalled = true; PickOutcome.Selected("/fallback") },
        )
        assertFailsWith<CancellationException> { picker.pickDirectory(null) }
        assertTrue(!secondCalled)
    }

    @Test
    fun `single-flight mutex rejects concurrent pickDirectory call`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val picker = FallbackFolderPicker(
            FolderPickerCandidate {
                entered.complete(Unit)
                release.await()
                PickOutcome.Selected("/first")
            },
        )
        val first = async { picker.pickDirectory(null) }
        entered.await()
        assertNull(picker.pickDirectory(null))
        release.complete(Unit)
        assertEquals("/first", first.await())
    }

    @Test
    fun `resolveExistingDirectory resolves directory or existing parent`() {
        assertNull(resolveExistingDirectory(null))
        assertNull(resolveExistingDirectory("   "))
        val tmp = File(System.getProperty("java.io.tmpdir"))
        assertEquals(tmp, resolveExistingDirectory(tmp.absolutePath))
        val childFile = File(tmp, "nonexistent-child-${System.nanoTime()}.md")
        assertEquals(tmp, resolveExistingDirectory(childFile.absolutePath))
    }
}
