package dev.mimir.desktop.picker

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PortalFolderPickerTest {
    @Test
    fun `request path is derived from the unique bus name and the handle token`() {
        assertEquals(
            "/org/freedesktop/portal/desktop/request/1_42/mimir_pick",
            PortalFolderPicker.requestPath(":1.42", "mimir_pick"),
        )
    }

    @Test
    fun `approved response yields the decoded folder path`() {
        // Портал есть только на Linux, но тест идёт на всей CI-матрице: путь ОС сравниваем в её собственной форме.
        assertEquals(
            File("/home/me/My Notes").absolutePath,
            PortalFolderPicker.pathFromResponse(0, listOf("file:///home/me/My%20Notes")),
        )
    }

    @Test
    fun `cancelled response yields null so the caller keeps its path`() {
        assertNull(PortalFolderPicker.pathFromResponse(1, emptyList()))
        assertNull(PortalFolderPicker.pathFromResponse(0, emptyList()))
    }

    @Test
    fun `non-file uris are ignored`() {
        assertNull(PortalFolderPicker.pathFromResponse(0, listOf("smb://server/share")))
    }
}
