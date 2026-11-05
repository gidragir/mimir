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
        assertEquals("cid", config.googleDeviceClientId)
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
                "MIMIR_GOOGLE_DEVICE_CLIENT_ID" to "env-id",
                "MIMIR_GOOGLE_DEVICE_CLIENT_SECRET" to "env-secret",
                "MIMIR_SYNC_INTERVAL" to "5",
                "MIMIR_ROOT_FOLDER" to "Root",
            ),
        )

        assertEquals(9200, config.port)
        assertEquals("/from/env", config.vaultPath)
        assertEquals("file-token", config.token)
        assertEquals("pw", config.password)
        assertEquals("env-id", config.googleDeviceClientId)
        assertEquals("env-secret", config.googleDeviceClientSecret)
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

    @Test
    fun `new env variables fill both clients`() {
        val config = DaemonConfig.fromEnv(
            mapOf(
                "MIMIR_GOOGLE_DESKTOP_CLIENT_ID" to "dId",
                "MIMIR_GOOGLE_DESKTOP_CLIENT_SECRET" to "dSec",
                "MIMIR_GOOGLE_DEVICE_CLIENT_ID" to "vId",
                "MIMIR_GOOGLE_DEVICE_CLIENT_SECRET" to "vSec",
            ),
        )
        assertEquals("dId", config.googleDesktopClientId)
        assertEquals("dSec", config.googleDesktopClientSecret)
        assertEquals("vId", config.googleDeviceClientId)
        assertEquals("vSec", config.googleDeviceClientSecret)
    }

    @Test
    fun `legacy env variables are device alias and new names win`() {
        val legacy = DaemonConfig.fromEnv(mapOf("MIMIR_GOOGLE_CLIENT_ID" to "old", "MIMIR_GOOGLE_CLIENT_SECRET" to "oldSec"))
        assertEquals("old", legacy.googleDeviceClientId)
        assertEquals("oldSec", legacy.googleDeviceClientSecret)
        assertNull(legacy.googleDesktopClientId)

        val both = DaemonConfig.fromEnv(mapOf("MIMIR_GOOGLE_CLIENT_ID" to "old", "MIMIR_GOOGLE_DEVICE_CLIENT_ID" to "new"))
        assertEquals("new", both.googleDeviceClientId)
    }

    @Test
    fun `empty Google client env variables are treated as absent`() {
        val dir = createTempDirectory("mimir-config")
        dir.resolve("config.json").writeText(
            """{"googleDesktopClientId":"fileD","googleDesktopClientSecret":"fileDSec",
               "googleDeviceClientId":"fileV","googleDeviceClientSecret":"fileVSec"}""",
        )
        val config = DaemonConfig.load(
            dir.toString(),
            mapOf(
                "MIMIR_GOOGLE_DESKTOP_CLIENT_ID" to "",
                "MIMIR_GOOGLE_DESKTOP_CLIENT_SECRET" to "",
                "MIMIR_GOOGLE_DEVICE_CLIENT_ID" to "",
                "MIMIR_GOOGLE_DEVICE_CLIENT_SECRET" to "",
            ),
        )
        assertEquals("fileD", config.googleDesktopClientId)
        assertEquals("fileDSec", config.googleDesktopClientSecret)
        assertEquals("fileV", config.googleDeviceClientId)
        assertEquals("fileVSec", config.googleDeviceClientSecret)

        val legacy = DaemonConfig.fromEnv(
            mapOf(
                "MIMIR_GOOGLE_CLIENT_ID" to "old",
                "MIMIR_GOOGLE_DEVICE_CLIENT_ID" to "",
                "MIMIR_GOOGLE_CLIENT_SECRET" to "",
            ),
        )
        assertEquals("old", legacy.googleDeviceClientId)
        assertNull(legacy.googleDeviceClientSecret)
    }

    @Test
    fun `legacy config json keys are device alias`() {
        val dir = createTempDirectory("mimir-config")
        dir.resolve("config.json").writeText("""{"googleClientId":"old","googleClientSecret":"oldSec"}""")
        val config = DaemonConfig.load(dir.toString(), emptyMap())
        assertEquals("old", config.googleDeviceClientId)
        assertEquals("oldSec", config.googleDeviceClientSecret)
    }

    @Test
    fun `toString redacts both client secrets`() {
        val text = DaemonConfig(googleDesktopClientSecret = "s3cr3t", googleDeviceClientSecret = "s3cr3t").toString()
        assertFalse("s3cr3t" in text)
    }

    @Test
    fun `sync parallelism defaults to 4`() {
        assertEquals(4, DaemonConfig.fromEnv(emptyMap()).syncParallelism)
    }

    @Test
    fun `sync parallelism from env`() {
        assertEquals(8, DaemonConfig.fromEnv(mapOf("MIMIR_SYNC_PARALLELISM" to "8")).syncParallelism)
    }

    @Test
    fun `sync parallelism out of range fails`() {
        for (bad in listOf("0", "17", "abc")) {
            val e = assertFailsWith<IllegalArgumentException> {
                DaemonConfig.fromEnv(mapOf("MIMIR_SYNC_PARALLELISM" to bad))
            }
            assertEquals("MIMIR_SYNC_PARALLELISM must be a number from 1 to 16, got '$bad'", e.message)
        }
    }

    @Test
    fun `sync parallelism is saved to and read from config json`() {
        val dir = createTempDirectory("mimir-config")
        DaemonConfig(syncParallelism = 6).save(dir.toString())

        assertEquals(6, DaemonConfig.readFile(dir.toString()).syncParallelism)
    }

    @Test
    fun `update check is enabled by default`() {
        assertTrue(DaemonConfig.fromEnv(emptyMap()).updateCheck)
    }

    @Test
    fun `MIMIR_UPDATE_CHECK false 0 off no disable it case-insensitively`() {
        for (value in listOf("false", "FALSE", "0", "off", "Off", "no", " false ")) {
            assertFalse(DaemonConfig.fromEnv(mapOf("MIMIR_UPDATE_CHECK" to value)).updateCheck, value)
        }
    }

    @Test
    fun `MIMIR_UPDATE_CHECK true 1 on enables it and overrides config json`() {
        val dir = createTempDirectory("mimir-config")
        dir.resolve("config.json").writeText("""{"updateCheck": false}""")
        assertFalse(DaemonConfig.load(dir.toString(), emptyMap()).updateCheck)
        for (value in listOf("true", "1", "on", "yes")) {
            assertTrue(DaemonConfig.load(dir.toString(), mapOf("MIMIR_UPDATE_CHECK" to value)).updateCheck, value)
        }
    }

    @Test
    fun `empty MIMIR_UPDATE_CHECK is treated as unset`() {
        assertTrue(DaemonConfig.fromEnv(mapOf("MIMIR_UPDATE_CHECK" to "")).updateCheck)
    }

    @Test
    fun `invalid MIMIR_UPDATE_CHECK fails mentioning the variable`() {
        val e = assertFailsWith<IllegalArgumentException> { DaemonConfig.fromEnv(mapOf("MIMIR_UPDATE_CHECK" to "maybe")) }
        assertTrue("MIMIR_UPDATE_CHECK" in e.message.orEmpty())
    }

    @Test
    fun `update check round-trips through config json`() {
        val dir = createTempDirectory("mimir-config")
        DaemonConfig(updateCheck = false).save(dir.toString())
        assertFalse(DaemonConfig.readFile(dir.toString()).updateCheck)
    }
}
