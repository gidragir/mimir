package dev.mimir.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DaemonConfigTest {
    @Test
    fun `defaults are 127_0_0_1 and 47821`() {
        val config = DaemonConfig.fromEnv(emptyMap())
        assertEquals("127.0.0.1", config.bind)
        assertEquals(47821, config.port)
        assertNull(config.vaultPath)
        assertNull(config.token)
    }

    @Test
    fun `env overrides bind and port`() {
        val config = DaemonConfig.fromEnv(
            mapOf("MIMIR_BIND" to "0.0.0.0", "MIMIR_PORT" to "9000", "MIMIR_VAULT" to "/vault", "MIMIR_TOKEN" to "t"),
        )
        assertEquals(DaemonConfig("0.0.0.0", 9000, "/vault", "t"), config)
    }

    @Test
    fun `invalid MIMIR_PORT fails with IllegalArgumentException mentioning MIMIR_PORT`() {
        val error = assertFailsWith<IllegalArgumentException> { DaemonConfig.fromEnv(mapOf("MIMIR_PORT" to "abc")) }
        assertTrue("MIMIR_PORT" in error.message.orEmpty())
    }
}
