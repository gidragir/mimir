package dev.mimir.desktop.picker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class MacFolderPickerTest {
    private val key = "apple.awt.fileDialogForDirectories"

    @Test
    fun `cancellation is rethrown`() = runTest {
        val picker = MacFolderPicker(openDialog = { _, _ -> throw CancellationException("x") })
        assertFailsWith<CancellationException> { picker.tryPick(null) }
    }

    @Test
    fun `failure yields Unavailable and restores the directories property`() = runTest {
        val before = System.getProperty(key)
        var during: String? = null
        val picker = MacFolderPicker(openDialog = { _, _ -> during = System.getProperty(key); error("headless") })
        assertIs<PickOutcome.Unavailable>(picker.tryPick(null))
        assertEquals("true", during)
        assertEquals(before, System.getProperty(key))
    }

    @Test
    fun `selected path and cancel are mapped`() = runTest {
        assertEquals(PickOutcome.Selected("/Users/me/Vault"), MacFolderPicker(openDialog = { _, _ -> "/Users/me/Vault" }).tryPick(null))
        assertEquals(PickOutcome.Cancelled, MacFolderPicker(openDialog = { _, _ -> null }).tryPick(null))
    }
}
