package dev.mimir.core

import kotlinx.coroutines.test.runTest
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private object ReversingCipher : Cipher {
    override fun encryptName(segment: String) = segment.reversed()
    override fun decryptName(segment: String) = segment.reversed()
    override fun encrypt(plain: Source): Source = xor(plain)
    override fun decrypt(encrypted: Source): Source = xor(encrypted)
    private fun xor(source: Source): Source =
        sourceOf(source.readByteArray().map { (it.toInt() xor 0x5A).toByte() }.toByteArray())
}

class EncryptedRemoteStorageTest {
    private val inner = InMemoryRemoteStorage()
    private val storage = EncryptedRemoteStorage(inner, ReversingCipher)
    private val plainPath = RemotePath("Здоровье/Анализы.md")
    private val plainBytes = "секрет".encodeToByteArray()

    @Test
    fun `path is encrypted per segment`() = runTest {
        storage.write(plainPath, sourceOf(plainBytes))
        val expected = "Здоровье".reversed() + "/" + "Анализы.md".reversed()
        assertEquals(listOf(expected), inner.list(RemotePath("Здоровье".reversed())).map { it.path.value })
    }

    @Test
    fun `content stored in inner is encrypted and read back plain`() = runTest {
        storage.write(plainPath, sourceOf(plainBytes))
        val innerPath = RemotePath("Здоровье".reversed() + "/" + "Анализы.md".reversed())
        val stored = inner.read(innerPath).readByteArray()
        assertEquals(false, stored.contentEquals(plainBytes))
        assertContentEquals(plainBytes, storage.read(plainPath).readByteArray())
    }

    @Test
    fun `list decrypts names`() = runTest {
        storage.write(plainPath, sourceOf(plainBytes))
        assertEquals(listOf(plainPath.value), storage.list(RemotePath("Здоровье")).map { it.path.value })
    }

    private suspend fun RemoteStorage.recurse(dir: RemotePath, into: MutableList<RemoteEntry> = mutableListOf()): List<RemoteEntry> {
        for (entry in list(dir)) {
            if (entry.isDirectory) recurse(entry.path, into) else into.add(entry)
        }
        return into
    }

    @Test
    fun `listAll delegates to inner listAll and decrypts every segment`() = runTest {
        val reserved = EncryptedRemoteStorage(inner, ReversingCipher, reservedRootNames = setOf(".canary"))
        inner.write(RemotePath(".canary"), sourceOf(byteArrayOf(1)))
        reserved.write(plainPath, sourceOf(plainBytes))
        reserved.write(RemotePath("a/b/c/deep.md"), sourceOf(plainBytes))
        reserved.write(RemotePath("top.md"), sourceOf(plainBytes))

        var innerCalls = 0
        val spyInner = object : RemoteStorage by inner {
            override suspend fun listAll(root: RemotePath, onProgress: (Int, Int) -> Unit): List<RemoteEntry> {
                innerCalls++
                return inner.listAll(root, onProgress)
            }
        }
        val spied = EncryptedRemoteStorage(spyInner, ReversingCipher, reservedRootNames = setOf(".canary"))

        var last = 0 to 0
        val all = spied.listAll { folders, files -> last = folders to files }

        assertEquals(1, innerCalls)
        assertEquals(reserved.recurse(RemotePath("")), all)
        assertEquals(setOf(plainPath.value, "a/b/c/deep.md", "top.md"), all.map { it.path.value }.toSet())
        assertEquals(true, last.first > 0)
        assertEquals(
            listOf("a/b/c/deep.md"),
            spied.listAll(RemotePath("a/b")).map { it.path.value },
        )
    }

    @Test
    fun `listAll fails on undecryptable names exactly like list`() = runTest {
        val strict = object : Cipher by ReversingCipher {
            override fun decryptName(segment: String): String {
                if (segment == "bad") throw CryptException("invalid name")
                return ReversingCipher.decryptName(segment)
            }
        }
        val s = EncryptedRemoteStorage(inner, strict)
        inner.write(RemotePath("bad"), sourceOf(byteArrayOf(1)))
        assertFailsWith<CryptException> { s.list(RemotePath("")) }
        assertFailsWith<CryptException> { s.listAll() }
    }

    @Test
    fun `reserved root names are hidden from list and changes`() = runTest {
        val reserved = EncryptedRemoteStorage(inner, ReversingCipher, reservedRootNames = setOf(".canary"))
        inner.write(RemotePath(".canary"), sourceOf(byteArrayOf(1)))
        reserved.write(plainPath, sourceOf(plainBytes))
        assertEquals(listOf("Здоровье"), reserved.list(RemotePath("")).map { it.path.value })
        val changes = reserved.getChanges("0").changes.map { it.path.value }
        assertEquals(listOf(plainPath.value), changes)
    }
}
