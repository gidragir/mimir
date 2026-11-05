package dev.mimir.core

import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EncryptedRemoteStorageRcloneTest {
    private val cipher get() = TestCiphers.all[0]
    private val inner = InMemoryRemoteStorage()
    private val storage get() = EncryptedRemoteStorage(inner, cipher)
    private val plainPath = RemotePath("Здоровье/Анализы мамы.md")
    private val plainBytes = "секрет".encodeToByteArray()

    @Test
    fun `stored data is encrypted per segment and reads back`() = runTest {
        TestCiphers.nonce = ByteArray(24) { it.toByte() }
        storage.write(plainPath, sourceOf(plainBytes))

        val encryptedDir = cipher.encryptName("Здоровье")
        val root = inner.list(RemotePath(""))
        assertEquals(listOf(encryptedDir), root.map { it.path.value })
        assertFalse(encryptedDir.any { it in 'а'..'я' || it == 'ё' })

        val encryptedPath = RemotePath("$encryptedDir/${cipher.encryptName("Анализы мамы.md")}")
        assertEquals(2, encryptedPath.segments.size)
        val stored = inner.read(encryptedPath).readByteArray()
        assertContentEquals("RCLONE".encodeToByteArray() + byteArrayOf(0, 0), stored.copyOfRange(0, 8))
        assertEquals(32 + plainBytes.size + 16, stored.size)

        assertContentEquals(plainBytes, storage.read(plainPath).readByteArray())
        assertEquals(listOf(plainPath.value), storage.list(RemotePath("Здоровье")).map { it.path.value })
    }
}
