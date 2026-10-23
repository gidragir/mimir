package dev.mimir.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChangeFeedStorageTest {
    @Test
    fun testInMemoryChangeFeedTracksModificationsAndDeletions() = runTest {
        val storage = InMemoryRemoteStorage()
        val token0 = storage.getLatestChangeToken()

        storage.write(RemotePath("folder/note.md"), kotlinx.io.Buffer().also { it.write("hello".encodeToByteArray()) })
        val changes1 = storage.getChanges(token0)
        assertEquals(1, changes1.changes.size)
        assertEquals(RemotePath("folder/note.md"), changes1.changes[0].path)
        assertEquals(false, changes1.changes[0].isDeleted)

        val token1 = changes1.nextToken
        storage.delete(RemotePath("folder/note.md"))
        val changes2 = storage.getChanges(token1)
        assertEquals(1, changes2.changes.size)
        assertEquals(RemotePath("folder/note.md"), changes2.changes[0].path)
        assertTrue(changes2.changes[0].isDeleted)
    }

    @Test
    fun testEncryptedRemoteStorageDecryptsChangesFeedPaths() = runTest {
        val rawStorage = InMemoryRemoteStorage()
        val cipher = RcloneCipher("test-password-12345", "test-salt-67890")
        val encryptedStorage = EncryptedRemoteStorage(rawStorage, cipher)

        val token0 = (encryptedStorage as ChangeFeedStorage).getLatestChangeToken()
        encryptedStorage.write(RemotePath("secret/diary.md"), kotlinx.io.Buffer().also { it.write("secret".encodeToByteArray()) })

        val result = (encryptedStorage as ChangeFeedStorage).getChanges(token0)
        assertEquals(1, result.changes.size)
        assertEquals(RemotePath("secret/diary.md"), result.changes[0].path)
    }
}
