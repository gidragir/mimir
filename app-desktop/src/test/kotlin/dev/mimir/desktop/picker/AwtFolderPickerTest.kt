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
    fun `picker falls back to user home when initialPath does not exist`() {
        val missing = File(System.getProperty("java.io.tmpdir"), "mimir-no-such-dir-${System.nanoTime()}")
        assertEquals(File(System.getProperty("user.home")), AwtFolderPicker().resolveInitialDirectory(missing.absolutePath))
    }
}
