package dev.mimir.daemon

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        assertEquals(30L, config.syncIntervalSeconds)
        assertEquals("MimirVault", config.rootFolder)
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

    @Test
    fun `load reads config json from the config dir`() {
        val dir = createTempDirectory("mimir-config")
        dir.resolve("config.json").writeText(
            """{"port": 9100, "vaultPath": "/from/file", "googleClientId": "cid", "syncIntervalSeconds": 60, "rootFolder": "Notes"}""",
        )

        val config = DaemonConfig.load(dir.toString(), emptyMap())

        assertEquals(9100, config.port)
        assertEquals("/from/file", config.vaultPath)
        assertEquals("cid", config.googleClientId)
        assertEquals(60L, config.syncIntervalSeconds)
        assertEquals("Notes", config.rootFolder)
        assertEquals(dir.toString(), config.configDir)
    }

    @Test
    fun `env overrides values from config json`() {
        val dir = createTempDirectory("mimir-config")
        dir.resolve("config.json").writeText("""{"port": 9100, "vaultPath": "/from/file", "token": "file-token"}""")

        val config = DaemonConfig.load(
            dir.toString(),
            mapOf(
                "MIMIR_PORT" to "9200",
                "MIMIR_VAULT" to "/from/env",
                "MIMIR_PASSWORD" to "pw",
                "MIMIR_GOOGLE_CLIENT_ID" to "env-id",
                "MIMIR_GOOGLE_CLIENT_SECRET" to "env-secret",
                "MIMIR_SYNC_INTERVAL" to "5",
                "MIMIR_ROOT_FOLDER" to "Root",
            ),
        )

        assertEquals(9200, config.port)
        assertEquals("/from/env", config.vaultPath)
        assertEquals("file-token", config.token)
        assertEquals("pw", config.password)
        assertEquals("env-id", config.googleClientId)
        assertEquals("env-secret", config.googleClientSecret)
        assertEquals(5L, config.syncIntervalSeconds)
        assertEquals("Root", config.rootFolder)
    }

    @Test
    fun `MIMIR_CONFIG_DIR selects the config directory`() {
        val dir = createTempDirectory("mimir-config")
        dir.resolve("config.json").writeText("""{"vaultPath": "/v"}""")

        val config = DaemonConfig.load(null, mapOf("MIMIR_CONFIG_DIR" to dir.toString()))

        assertEquals("/v", config.vaultPath)
    }

    @Test
    fun `missing config json yields defaults`() {
        val config = DaemonConfig.load(createTempDirectory("mimir-config").toString(), emptyMap())
        assertEquals(47821, config.port)
    }

    @Test
    fun `broken config json fails with a readable message`() {
        val dir = createTempDirectory("mimir-config")
        dir.resolve("config.json").writeText("{ not json")

        val error = assertFailsWith<IllegalArgumentException> { DaemonConfig.load(dir.toString(), emptyMap()) }

        assertTrue("config.json" in error.message.orEmpty())
    }

    @Test
    fun `invalid sync interval is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> {
            DaemonConfig.fromEnv(mapOf("MIMIR_SYNC_INTERVAL" to "0"))
        }
        assertTrue("MIMIR_SYNC_INTERVAL" in error.message.orEmpty())
    }

    @Test
    fun `password file is read and trimmed, inline password wins`() {
        val file = Files.createTempFile("mimir-pw", ".txt").also { it.writeText("from-file\n") }

        assertEquals("from-file", DaemonConfig.fromEnv(mapOf("MIMIR_PASSWORD_FILE" to file.toString())).resolvePassword())
        assertEquals(
            "inline",
            DaemonConfig.fromEnv(mapOf("MIMIR_PASSWORD" to "inline", "MIMIR_PASSWORD_FILE" to file.toString())).resolvePassword(),
        )
        assertNull(DaemonConfig.fromEnv(emptyMap()).resolvePassword())
    }

    @Test
    fun `toString never prints secrets`() {
        val text = DaemonConfig.fromEnv(
            mapOf("MIMIR_PASSWORD" to "pw-secret", "MIMIR_TOKEN" to "tok-secret", "MIMIR_GOOGLE_CLIENT_SECRET" to "cs-secret"),
        ).toString()

        assertFalse("pw-secret" in text || "tok-secret" in text || "cs-secret" in text)
    }
}
