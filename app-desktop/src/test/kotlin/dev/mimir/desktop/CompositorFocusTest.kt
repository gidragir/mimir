package dev.mimir.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompositorFocusTest {
    private val windows = """[
        {"id":4,"title":"Claude","app_id":"com.anthropic.Claude","pid":6471},
        {"id":60,"title":"Mimir","app_id":"dev-mimir-desktop-MainKt","pid":3211},
        {"id":61,"title":"Mimir","app_id":"other","pid":99}
    ]"""

    @Test
    fun `niri window is found by process id first`() {
        assertEquals(60L, CompositorFocus.niriWindowId(windows, pid = 3211, title = "Mimir"))
        assertEquals(61L, CompositorFocus.niriWindowId(windows, pid = 99, title = "Mimir"))
    }

    @Test
    fun `niri window falls back to the title when the pid is not listed`() {
        assertEquals(60L, CompositorFocus.niriWindowId(windows, pid = 1, title = "Mimir"))
    }

    @Test
    fun `niri window is null when nothing matches or the output is garbage`() {
        assertNull(CompositorFocus.niriWindowId(windows, pid = 1, title = "Nope"))
        assertNull(CompositorFocus.niriWindowId("not json", pid = 1, title = "Mimir"))
    }

    @Test
    fun `focus asks niri to focus the found window`() {
        val ran = mutableListOf<List<String>>()
        val focus = CompositorFocus(
            env = mapOf("NIRI_SOCKET" to "/run/niri.sock"),
            run = { cmd -> ran += cmd; if (cmd.contains("windows")) windows else "" },
            pid = 3211,
            title = "Mimir",
        )
        assertEquals(true, focus.focusWindow())
        assertEquals(listOf("niri", "msg", "action", "focus-window", "--id", "60"), ran.last())
    }

    @Test
    fun `focus does nothing outside a niri session`() {
        val focus = CompositorFocus(env = emptyMap(), run = { error("unexpected") }, pid = 1, title = "Mimir")
        assertEquals(false, focus.focusWindow())
    }
}
