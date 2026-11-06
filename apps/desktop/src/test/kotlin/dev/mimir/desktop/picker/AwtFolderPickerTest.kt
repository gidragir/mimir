package dev.mimir.desktop.picker

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class AwtFolderPickerTest {
    @Test
    fun `picker fallback directory resolves user home when initialPath is null or blank`() {
        val picker = AwtFolderPicker()
        val home = File(System.getProperty("user.home"))
        assertEquals(home, picker.resolveInitialDirectory(null))
        assertEquals(home, picker.resolveInitialDirectory("  "))

        val custom = File(System.getProperty("java.io.tmpdir"))
        assertEquals(custom, picker.resolveInitialDirectory(custom.absolutePath))
    }

    @Test
    fun `picker uses existing parent when initialPath does not exist and user home when no parent exists`() {
        val tmp = File(System.getProperty("java.io.tmpdir"))
        val missing = File(tmp, "mimir-no-such-dir-${System.nanoTime()}")
        assertEquals(tmp, AwtFolderPicker().resolveInitialDirectory(missing.absolutePath))
        val deep = File(missing, "a/b")
        assertEquals(File(System.getProperty("user.home")), AwtFolderPicker().resolveInitialDirectory(deep.absolutePath))
    }
}
