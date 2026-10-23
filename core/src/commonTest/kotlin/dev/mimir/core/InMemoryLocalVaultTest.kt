package dev.mimir.core

import dev.mimir.core.testing.InMemoryLocalVault
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryLocalVaultTest {

    private fun bufferOf(text: String): Buffer =
        Buffer().also { it.write(text.encodeToByteArray()) }

    @Test
    fun writeAndReadByPath() = runTest {
        val vault = InMemoryLocalVault()
        assertTrue(vault.list().isEmpty())
        assertNull(vault.getEntry("note.md"))

        val content = "Hello Mimir Vault"
        val entry = vault.write("note.md", bufferOf(content))

        assertEquals("note.md", entry.relativePath)
        assertEquals(content.length.toLong(), entry.size)
        assertEquals(1, vault.list().size)
        assertEquals(entry, vault.getEntry("note.md"))

        val readSource = vault.read(entry.id)
        val readBytes = readSource.readByteArray()
        assertContentEquals(content.encodeToByteArray(), readBytes)
    }

    @Test
    fun updateAndChangesFlow() = runTest {
        val vault = InMemoryLocalVault()
        val entry1 = vault.write("folder/note.md", bufferOf("version 1"))

        val change1 = vault.changes().first()
        assertTrue(change1 is VaultChange.Created)
        assertEquals(entry1.id, change1.id)

        val entry2 = vault.write("folder/note.md", bufferOf("version 2 longer"))
        assertEquals(entry1.id, entry2.id)
        assertEquals("folder/note.md", entry2.relativePath)
        assertEquals(entry2, vault.getEntry("folder/note.md"))

        vault.deleteByPath("folder/note.md")
        assertNull(vault.getEntry("folder/note.md"))
        assertTrue(vault.list().isEmpty())
    }

    @Test
    fun deleteByIdAndPath() = runTest {
        val vault = InMemoryLocalVault()
        val entry = vault.write("test.md", bufferOf("content"))
        assertNotNull(vault.getEntry("test.md"))

        vault.delete(entry.id)
        assertNull(vault.getEntry("test.md"))
        assertTrue(vault.list().isEmpty())
    }

    @Test
    fun failureSimulation() = runTest {
        val vault = InMemoryLocalVault()
        val entry = vault.write("doc.md", bufferOf("data"))

        vault.throwOnRead = true
        assertFailsWith<IOException> {
            vault.read(entry.id)
        }

        vault.throwOnRead = false
        val okBytes = vault.read(entry.id).readByteArray()
        assertContentEquals("data".encodeToByteArray(), okBytes)

        vault.throwOnWrite = true
        assertFailsWith<IOException> {
            vault.write("another.md", bufferOf("fail"))
        }
    }
}
