package dev.mimir.core

import dev.mimir.core.sync.FileSyncRecord
import dev.mimir.core.sync.SyncIndex
import dev.mimir.core.sync.SyncIndexJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class SyncIndexJsonTest {

    @Test
    fun roundTripWithAllFields() {
        val record1 = FileSyncRecord(
            relativePath = "docs/note.md",
            localId = VaultFileId("local-1"),
            baseHash = "hash123",
            localSize = 100L,
            localModifiedAt = Instant.fromEpochSeconds(1700000000L, 500),
            remotePath = RemotePath("remote/path.md"),
            remoteRevision = "rev-abc",
            remoteModifiedAt = Instant.fromEpochSeconds(1700000050L, 200),
            remoteSize = 98L,
        )
        val record2 = FileSyncRecord(
            relativePath = "image.png",
            localId = VaultFileId("local-2"),
            baseHash = "hash456",
            localSize = 5000L,
            localModifiedAt = Instant.fromEpochSeconds(1700001000L, 0),
            remotePath = RemotePath("remote/img.png"),
            remoteRevision = null,
            remoteModifiedAt = Instant.fromEpochSeconds(1700001000L, 0),
            remoteSize = 5000L,
        )
        val index = SyncIndex(
            version = 1,
            records = mapOf(
                record1.relativePath to record1,
                record2.relativePath to record2,
            ),
        )

        val encoded = SyncIndexJson.encode(index)
        val decoded = SyncIndexJson.decode(encoded)

        assertEquals(index.version, decoded.version)
        assertEquals(2, decoded.records.size)
        assertEquals(record1, decoded.records[record1.relativePath])
        assertEquals(record2, decoded.records[record2.relativePath])
        assertNull(decoded.records[record2.relativePath]?.remoteRevision)
    }
}
