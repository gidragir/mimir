package dev.mimir.desktop.tray

import kotlin.test.Test
import kotlin.test.assertIs

class TrayTest {
    @Test
    fun `windows with tray support gets AwtTray`() {
        assertIs<AwtTray>(createTray("Windows 11") { true })
    }

    @Test
    fun `linux gets NoTray`() {
        assertIs<NoTray>(createTray("Linux") { true })
    }

    @Test
    fun `windows without tray support gets NoTray`() {
        assertIs<NoTray>(createTray("Windows 11") { false })
    }

    @Test
    fun `NoTray show does not throw`() {
        val tray = NoTray()
        tray.show("Mimir: not connected", onOpen = {}, onQuit = {})
        tray.update("Mimir: syncing")
        tray.close()
    }
}
