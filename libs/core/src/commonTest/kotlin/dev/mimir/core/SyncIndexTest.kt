package dev.mimir.core

import dev.mimir.core.sync.FileSyncRecord
import dev.mimir.core.sync.InMemorySyncStateStore
import dev.mimir.core.sync.SyncIndex
import dev.mimir.core.sync.SyncStateStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class SyncIndexTest {

    private val now = Instant.fromEpochMilliseconds(1729720000000L)

    private fun sampleRecord(path: String, hash: String = "hash-$path") = FileSyncRecord(
        relativePath = path,
        localId = VaultFileId("local-$path"),
        baseHash = hash,
        localSize = 100L,
        localModifiedAt = now,
        remotePath = RemotePath(path),
        remoteRevision = "rev-1",
        remoteModifiedAt = now,
        remoteSize = 100L,
    )

    @Test
    fun syncIndexOperations() {
        val emptyIndex = SyncIndex()
        assertEquals(1, emptyIndex.version)
        assertNull(emptyIndex.find("note.md"))

        val rec1 = sampleRecord("note.md")
        val rec2 = sampleRecord("folder/sub.md")

        val index1 = emptyIndex.withRecord(rec1)
        assertEquals(rec1, index1.find("note.md"))
        assertNull(index1.find("folder/sub.md"))

        val index2 = index1.withRecord(rec2)
        assertEquals(rec1, index2.find("note.md"))
        assertEquals(rec2, index2.find("folder/sub.md"))
        assertEquals(2, index2.records.size)

        val updatedRec1 = rec1.copy(baseHash = "updated-hash", localSize = 150L)
        val index3 = index2.withRecord(updatedRec1)
        assertEquals(updatedRec1, index3.find("note.md"))
        assertEquals(2, index3.records.size)

        val index4 = index3.withoutRecord("note.md")
        assertNull(index4.find("note.md"))
        assertEquals(rec2, index4.find("folder/sub.md"))
        assertEquals(1, index4.records.size)
    }

    @Test
    fun inMemorySyncStateStoreOperations() = runTest {
        val store: SyncStateStore = InMemorySyncStateStore()
        val initial = store.load()
        assertEquals(0, initial.records.size)

        val rec = sampleRecord("test.md")
        val updated = initial.withRecord(rec)
        store.save(updated)

        val loaded = store.load()
        assertEquals(1, loaded.records.size)
        assertEquals(rec, loaded.find("test.md"))
    }
}
