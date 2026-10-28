package dev.mimir.daemon

import java.nio.file.Files
import kotlin.test.Test

class PasswordFileSecurityTest {
    @Test
    fun `password file readable by group or others is reported as insecure`() {
        val file = Files.createTempFile("mimir-pass", ".txt")
        Files.writeString(file, "secret")
        // На Windows (NTFS) POSIX-прав нет: судить не по чему, предупреждения нет.
        if ("posix" !in file.fileSystem.supportedFileAttributeViews()) {
            kotlin.test.assertFalse(DaemonConfig(passwordFile = file.toString()).isPasswordFileInsecure())
            return
        }
        Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"))
        kotlin.test.assertTrue(DaemonConfig(passwordFile = file.toString()).isPasswordFileInsecure())
        Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        kotlin.test.assertFalse(DaemonConfig(passwordFile = file.toString()).isPasswordFileInsecure())
    }
}
