package dev.mimir.daemon

import dev.mimir.drive.auth.GoogleClientCredentials
import dev.mimir.drive.auth.GoogleClientKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GoogleClientsTest {
    private fun embedded(desktopId: String = "", desktopSecret: String = "", deviceId: String = "", deviceSecret: String = "") =
        EmbeddedGoogleClients(desktopId, desktopSecret, deviceId, deviceSecret)

    @Test
    fun `configured values override embedded`() {
        val (desktop, _) = DaemonConfig(googleDesktopClientId = "cfg").googleCredentials(embedded(desktopId = "emb"))
        assertEquals("cfg", desktop?.clientId)
        assertEquals(GoogleClientKind.Desktop, desktop?.kind)
    }

    @Test
    fun `embedded values are used when config is empty`() {
        val (desktop, device) = DaemonConfig().googleCredentials(embedded(desktopId = "emb", desktopSecret = "sec", deviceId = "dev"))
        assertEquals(GoogleClientCredentials(GoogleClientKind.Desktop, "emb", "sec"), desktop)
        assertEquals(GoogleClientCredentials(GoogleClientKind.Device, "dev", null), device)
    }

    @Test
    fun `empty string means absent`() {
        val (desktop, device) = DaemonConfig().googleCredentials(embedded(desktopId = ""))
        assertNull(desktop)
        assertNull(device)
    }
}
