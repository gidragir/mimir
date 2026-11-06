package dev.mimir.desktop.tray

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

class TrayTest {
    private object FakeLinuxTray : Tray {
        override val available = true
        override fun show(status: String, onOpen: () -> Unit, onSyncNow: () -> Unit, onQuit: () -> Unit, onActivate: (TrayClickAnchor?) -> Unit) = Unit
        override fun update(status: String) = Unit
        override fun close() = Unit
    }

    @Test
    fun `createTray returns NoTray when platform not supported`() {
        val tray = createTray(osName = "UnknownOS", awtTraySupported = { false })
        assertIs<NoTray>(tray)
        assertFalse(tray.available)
    }

    @Test
    fun `windows with tray support gets AwtTray`() {
        assertIs<AwtTray>(createTray("Windows 11", { true }))
    }

    @Test
    fun `macos with tray support gets AwtTray`() {
        assertIs<AwtTray>(createTray("Mac OS X", { true }))
    }

    @Test
    fun `macos without tray support gets NoTray`() {
        assertIs<NoTray>(createTray("Mac OS X", { false }))
    }

    @Test
    fun `windows without tray support gets NoTray`() {
        assertIs<NoTray>(createTray("Windows 11", { false }))
    }

    @Test
    fun `linux gets the D-Bus tray even though AWT claims support`() {
        assertSame(FakeLinuxTray, createTray("Linux", { true }, dbusTray = { FakeLinuxTray }))
    }

    @Test
    fun `NoTray handles lifecycle without exceptions`() {
        val tray = NoTray()
        tray.show("Mimir: not connected", onOpen = {}, onSyncNow = {}, onQuit = {})
        tray.update("Mimir: syncing")
        tray.close()
    }
}
