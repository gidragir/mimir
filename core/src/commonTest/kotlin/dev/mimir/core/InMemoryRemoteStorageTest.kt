package dev.mimir.core

import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal fun sourceOf(bytes: ByteArray): Buffer = Buffer().also { it.write(bytes) }

class InMemoryRemoteStorageTest {
    private val storage = InMemoryRemoteStorage()

    @Test
    fun `write then read returns same bytes`() = runTest {
        val bytes = "привет".encodeToByteArray()
        storage.write(RemotePath("a.md"), sourceOf(bytes))
        assertContentEquals(bytes, storage.read(RemotePath("a.md")).readByteArray())
    }

    @Test
    fun `list returns direct children only`() = runTest {
        storage.write(RemotePath("a/x.md"), sourceOf(byteArrayOf(1)))
        storage.write(RemotePath("a/b/y.md"), sourceOf(byteArrayOf(2)))
        val entries = storage.list(RemotePath("a")).associateBy { it.path.value }
        assertEquals(setOf("a/x.md", "a/b"), entries.keys)
        assertEquals(false, entries.getValue("a/x.md").isDirectory)
        assertEquals(true, entries.getValue("a/b").isDirectory)
    }

    @Test
    fun `delete removes entry and stat returns null`() = runTest {
        storage.write(RemotePath("a.md"), sourceOf(byteArrayOf(1)))
        storage.delete(RemotePath("a.md"))
        assertNull(storage.stat(RemotePath("a.md")))
    }
}
