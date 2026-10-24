package dev.mimir.core.fs

import dev.mimir.core.VaultFileId
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.readByteArray
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSystemLocalVaultTest {
    private val root = Files.createTempDirectory("mimir-vault-test")
    private val vault = FileSystemLocalVault(Path(root.toString()))

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    private fun put(relative: String, text: String) {
        val file = root.resolve(relative)
        file.parent.createDirectories()
        file.writeText(text)
    }

    private fun source(text: String) = Buffer().also { it.write(text.encodeToByteArray()) }

    @Test
    fun `list walks recursively and skips service files`() = runTest {
        put("Notes/a.md", "a")
        put("Notes/deep/b.md", "bb")
        put("top.md", "t")
        put(".git/HEAD", "ref")
        put(".obsidian/workspace.json", "{}")
        put(".obsidian/cache/data", "x")
        put(".obsidian/app.json", "{}")
        put(".trash/old.md", "x")
        put("Notes/.mimir_tmp_123", "partial")

        val paths = vault.list().map { it.relativePath }.sorted()

        assertEquals(listOf(".obsidian/app.json", "Notes/a.md", "Notes/deep/b.md", "top.md"), paths)
    }

    @Test
    fun `entries carry stable id, size and modification time`() = runTest {
        put("Notes/a.md", "hello")

        val entry = assertNotNull(vault.getEntry("Notes/a.md"))

        assertEquals(VaultFileId("fs:Notes/a.md"), entry.id)
        assertEquals(5L, entry.size)
        assertEquals(root.resolve("Notes/a.md").toFile().lastModified(), entry.modifiedAt.toEpochMilliseconds())
        assertNull(vault.getEntry("Notes/missing.md"))
    }

    @Test
    fun `getEntry hides excluded paths`() = runTest {
        put(".git/HEAD", "ref")

        assertNull(vault.getEntry(".git/HEAD"))
    }

    @Test
    fun `write creates parents and read returns content`() = runTest {
        val entry = vault.write("New/Dir/c.md", source("текст"))

        assertEquals("New/Dir/c.md", entry.relativePath)
        assertEquals("текст", root.resolve("New/Dir/c.md").readText())
        assertEquals("текст", vault.read(entry.id).readByteArray().decodeToString())
    }

    @Test
    fun `write by id replaces content`() = runTest {
        put("a.md", "old")

        vault.write(VaultFileId("fs:a.md"), source("new"))

        assertEquals("new", root.resolve("a.md").readText())
    }

    @Test
    fun `write leaves no temp files behind and replaces atomically`() = runTest {
        put("Notes/a.md", "old")

        vault.write("Notes/a.md", source("new"))

        assertEquals("new", root.resolve("Notes/a.md").readText())
        assertEquals(listOf("a.md"), root.resolve("Notes").listDirectoryEntries().map { it.fileName.toString() })
    }

    @Test
    fun `failed write keeps the original file intact`() = runTest {
        put("Notes/a.md", "original")
        val broken = object : kotlinx.io.RawSource {
            private var first = true
            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
                if (first) {
                    first = false
                    sink.write("part".encodeToByteArray())
                    return 4
                }
                throw kotlinx.io.IOException("connection lost")
            }
            override fun close() {}
        }

        val failure = runCatching { vault.write("Notes/a.md", broken.buffered()) }

        assertTrue(failure.isFailure)
        assertEquals("original", root.resolve("Notes/a.md").readText())
        assertEquals(listOf("a.md"), root.resolve("Notes").listDirectoryEntries().map { it.fileName.toString() })
    }

    @Test
    fun `deleteByPath and delete by id remove files`() = runTest {
        put("a.md", "a")
        put("b.md", "b")

        vault.deleteByPath("a.md")
        vault.delete(VaultFileId("fs:b.md"))

        assertFalse(root.resolve("a.md").exists())
        assertFalse(root.resolve("b.md").exists())
        vault.deleteByPath("a.md") // идемпотентно
    }

    @Test
    fun `paths escaping the vault are rejected`() = runTest {
        val failure = runCatching { vault.write("../evil.md", source("x")) }

        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `list does not follow symbolic links`() = runTest {
        put("real/a.md", "a")
        put("note.md", "n")
        val outside = Files.createTempDirectory("mimir-vault-outside")
        try {
            outside.resolve("secret.md").writeText("s")
            Files.createSymbolicLink(root.resolve("linkdir"), outside)
            Files.createSymbolicLink(root.resolve("linkfile.md"), root.resolve("note.md"))
            val paths = vault.list().map { it.relativePath }
            assertEquals(listOf("note.md", "real/a.md"), paths.sorted())
            assertNull(vault.getEntry("linkdir/secret.md"))
            assertNull(vault.getEntry("linkfile.md"))
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `list skips node_modules and nested git at any depth`() = runTest {
        put("note.md", "n")
        put("node_modules/pkg/index.js", "x")
        put(".obsidian/plugins/p/node_modules/q/index.js", "x")
        put(".obsidian/plugins/p/main.js", "m")
        put("sub/repo/.git/HEAD", "ref")
        assertEquals(
            listOf(".obsidian/plugins/p/main.js", "note.md"),
            vault.list().map { it.relativePath }.sorted(),
        )
        assertNull(vault.getEntry("node_modules/pkg/index.js"))
        assertNull(vault.getEntry("sub/repo/.git/HEAD"))
    }

    @Test
    fun `write read and delete refuse paths through symbolic links`() = runTest {
        val outside = Files.createTempDirectory("mimir-vault-outside")
        try {
            outside.resolve("secret.md").writeText("s")
            Files.createSymbolicLink(root.resolve("linkdir"), outside)

            assertTrue(runCatching { vault.write("linkdir/new.md", source("x")) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { vault.deleteByPath("linkdir/secret.md") }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { vault.read(VaultFileId("fs:linkdir/secret.md")) }.exceptionOrNull() is IllegalArgumentException)

            assertFalse(outside.resolve("new.md").exists())
            assertEquals("s", outside.resolve("secret.md").readText())
        } finally {
            outside.toFile().deleteRecursively()
        }
    }
}
