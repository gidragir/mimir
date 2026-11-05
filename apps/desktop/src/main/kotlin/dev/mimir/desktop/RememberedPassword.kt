package dev.mimir.desktop

import dev.mimir.daemon.DaemonConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

private const val PASSWORD_FILE = "password"

/**
 * «Запомнить на этом устройстве»: пароль лежит в файле `password` (права 0600 на POSIX) в каталоге конфигурации,
 * а `config.json` ссылается на него через `passwordFile`; демон читает его при следующем запуске.
 */
fun rememberPassword(configDir: Path, password: String) {
    Files.createDirectories(configDir)
    val target = configDir.resolve(PASSWORD_FILE)
    val posix = configDir.fileSystem.supportedFileAttributeViews().contains("posix")
    val temp = if (posix) {
        Files.createTempFile(configDir, PASSWORD_FILE, ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    } else {
        Files.createTempFile(configDir, PASSWORD_FILE, ".tmp")
    }
    try {
        Files.writeString(temp, password)
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temp)
    }
    DaemonConfig.readFile(configDir.toString()).copy(passwordFile = target.toString()).save(configDir.toString())
}

fun forgetPassword(configDir: Path) {
    Files.deleteIfExists(configDir.resolve(PASSWORD_FILE))
    DaemonConfig.readFile(configDir.toString()).copy(passwordFile = null).save(configDir.toString())
}
