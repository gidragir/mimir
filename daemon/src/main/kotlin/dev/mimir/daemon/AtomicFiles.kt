package dev.mimir.daemon

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** Пишет файл через временный файл в том же каталоге; [ownerOnly] — права 0600 на POSIX. */
internal fun writeFileAtomically(target: Path, content: String, ownerOnly: Boolean = false) {
    val dir = target.toAbsolutePath().parent
    Files.createDirectories(dir)
    val posix = ownerOnly && dir.fileSystem.supportedFileAttributeViews().contains("posix")
    val temp = if (posix) {
        Files.createTempFile(dir, target.fileName.toString(), ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    } else {
        Files.createTempFile(dir, target.fileName.toString(), ".tmp")
    }
    try {
        Files.writeString(temp, content)
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temp)
    }
}
