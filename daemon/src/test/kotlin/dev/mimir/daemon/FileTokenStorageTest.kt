package dev.mimir.daemon

import dev.mimir.drive.auth.OAuthTokens
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileTokenStorageTest {
    private val dir = createTempDirectory("mimir-tokens")
    private val file = dir.resolve("nested/tokens.json")
    private val tokens = OAuthTokens("access", "refresh", 1_800_000_000L, "scope", "Bearer")

    @Test
    fun `load returns null when nothing is stored`() = runTest {
        assertNull(FileTokenStorage(file).load())
    }

    @Test
    fun `saved tokens survive a restart`() = runTest {
        FileTokenStorage(file).save(tokens)

        assertEquals(tokens, FileTokenStorage(file).load())
    }

    @Test
    fun `tokens file is readable by the owner only`() = runTest {
        FileTokenStorage(file).save(tokens)
        FileTokenStorage(file).save(tokens.copy(accessToken = "second"))

        if ("posix" in file.fileSystem.supportedFileAttributeViews()) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        }
        assertEquals(listOf("tokens.json"), file.parent.listDirectoryEntries().map { it.fileName.toString() })
    }

    @Test
    fun `clear removes the file`() = runTest {
        val storage = FileTokenStorage(file).also { it.save(tokens) }

        storage.clear()

        assertNull(storage.load())
        assertTrue(!Files.exists(file))
    }

    @Test
    fun `corrupt file is treated as not signed in`() = runTest {
        Files.createDirectories(file.parent)
        Files.writeString(file, "{ garbage")

        assertNull(FileTokenStorage(file).load())
    }
}
