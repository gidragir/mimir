package dev.mimir.desktop

import dev.mimir.daemon.DaemonConfig
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RememberedPasswordTest {
    private val dir = Files.createTempDirectory("mimir-remember")

    @Test
    fun `stores the password in an owner-only file and points the config at it`() {
        DaemonConfig(vaultPath = "/vault").save(dir.toString())
        rememberPassword(dir, "s3cret")

        val file = dir.resolve("password")
        assertEquals("s3cret", Files.readString(file))
        // На Windows (NTFS) POSIX-прав нет: файл закрыт ACL профиля пользователя (%APPDATA%).
        if (file.fileSystem.supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        }
        val config = DaemonConfig.load(dir.toString(), emptyMap())
        assertEquals(file.toString(), config.passwordFile)
        assertEquals("/vault", config.vaultPath)
        assertEquals("s3cret", config.resolvePassword())
    }

    @Test
    fun `replaces a previously remembered password`() {
        rememberPassword(dir, "old")
        rememberPassword(dir, "new")
        assertEquals("new", Files.readString(dir.resolve("password")))
    }

    @Test
    fun `forgetting removes the file and the config entry`() {
        rememberPassword(dir, "x")
        forgetPassword(dir)
        assertEquals(false, Files.exists(dir.resolve("password")))
        assertNull(DaemonConfig.load(dir.toString(), emptyMap()).passwordFile)
    }
}
