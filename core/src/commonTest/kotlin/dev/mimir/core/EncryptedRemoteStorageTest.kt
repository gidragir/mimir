package dev.mimir.core

import kotlinx.coroutines.test.runTest
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

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
}
