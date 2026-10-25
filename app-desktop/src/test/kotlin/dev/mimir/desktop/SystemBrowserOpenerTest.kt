package dev.mimir.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SystemBrowserOpenerTest {
    private val url = "https://accounts.google.com/o/oauth2/v2/auth?x=1"

    @Test
    fun `linux opens the default browser through xdg-open`() {
        val launched = mutableListOf<List<String>>()
        SystemBrowserOpener("Linux", launch = { launched += it }, browse = { error("unexpected") }).openUri(url)
        assertEquals(listOf(listOf("xdg-open", url)), launched)
    }

    @Test
    fun `falls back to the AWT desktop browser when xdg-open cannot start`() {
        val browsed = mutableListOf<String>()
        SystemBrowserOpener("Linux", launch = { throw java.io.IOException("no xdg-open") }, browse = { browsed += it }).openUri(url)
        assertEquals(listOf(url), browsed)
    }

    @Test
    fun `other systems use the AWT desktop browser`() {
        val browsed = mutableListOf<String>()
        SystemBrowserOpener("Windows 11", launch = { error("unexpected") }, browse = { browsed += it }).openUri(url)
        assertEquals(listOf(url), browsed)
    }

    @Test
    fun `refuses to open anything but web links`() {
        val opener = SystemBrowserOpener("Linux", launch = { error("unexpected") }, browse = { error("unexpected") })
        assertFailsWith<IllegalArgumentException> { opener.openUri("file:///etc/passwd") }
    }
}
