package dev.mimir.build

import kotlin.test.Test
import kotlin.test.assertEquals

class KarmaConfigTest {
    @Test
    fun `build-prefixed variable wins over plain one`() {
        val env = mapOf(
            "MIMIR_BUILD_GOOGLE_DEVICE_CLIENT_ID" to "build-id",
            "MIMIR_GOOGLE_DEVICE_CLIENT_ID" to "plain-id",
        )
        assertEquals("build-id", resolveGoogleSecret("DEVICE_CLIENT_ID", env))
    }

    @Test
    fun `plain variable is used when build-prefixed one is absent`() {
        val env = mapOf("MIMIR_GOOGLE_DEVICE_CLIENT_ID" to "plain-id")
        assertEquals("plain-id", resolveGoogleSecret("DEVICE_CLIENT_ID", env))
    }

    @Test
    fun `missing variables resolve to empty string`() {
        assertEquals("", resolveGoogleSecret("DEVICE_CLIENT_ID", emptyMap()))
    }
}
