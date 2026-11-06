package dev.mimir.desktop.picker

import dev.mimir.ui.picker.PlatformFolderPicker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DefaultFolderPickerRoutingTest {
    private val executor = ProcessExecutor { _, _ -> ProcessResult(0, "") }

    private fun types(os: String): List<Class<*>> {
        val picker: PlatformFolderPicker = defaultFolderPicker(osName = os, executor = executor)
        return (picker as FallbackFolderPicker).candidates.map { it::class.java }
    }

    @Test
    fun `windows chain`() = assertEquals(listOf(WindowsFolderPicker::class.java, AwtFolderPicker::class.java), types("Windows 11"))

    @Test
    fun `mac chain`() = assertEquals(listOf(MacFolderPicker::class.java, AwtFolderPicker::class.java), types("Mac OS X"))

    @Test
    fun `linux chain`() = assertEquals(
        listOf(PortalFolderPicker::class.java, ZenityFolderPicker::class.java, AwtFolderPicker::class.java),
        types("Linux"),
    )

    @Test
    fun `other systems use swing only`() { assertIs<AwtFolderPicker>(defaultFolderPicker(osName = "FreeBSD", executor = executor)) }
}
