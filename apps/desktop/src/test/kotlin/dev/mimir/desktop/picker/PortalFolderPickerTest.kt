package dev.mimir.desktop.picker

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
            PickOutcome.Selected(File("/home/me/My Notes").absolutePath),
            PortalFolderPicker.outcomeFromResponse(0, listOf("file:///home/me/My%20Notes")),
        )
    }

    @Test
    fun `cancelled response yields Cancelled so the chain stops`() {
        assertEquals(PickOutcome.Cancelled, PortalFolderPicker.outcomeFromResponse(1, emptyList()))
        assertEquals(PickOutcome.Cancelled, PortalFolderPicker.outcomeFromResponse(0, emptyList()))
    }

    @Test
    fun `non-file uris are Unavailable`() {
        assertIs<PickOutcome.Unavailable>(PortalFolderPicker.outcomeFromResponse(0, listOf("smb://server/share")))
    }
}
