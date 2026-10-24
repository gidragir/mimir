package dev.mimir.daemon

import dev.mimir.core.RemotePath
import dev.mimir.core.VaultFileId
import dev.mimir.core.sync.FileSyncRecord
import dev.mimir.core.sync.SyncIndex
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class JsonSyncStateStoreTest {
    private val dir = createTempDirectory("mimir-index")
    private val file = dir.resolve("sub/index.json")

    private val record = FileSyncRecord(
        relativePath = "Notes/a.md",
        localId = VaultFileId("fs:Notes/a.md"),
        baseHash = "abc123",
        localSize = 12,
        localModifiedAt = Instant.fromEpochSeconds(1_800_000_000, 123_000_000),
        remotePath = RemotePath("enc1/enc2"),
        remoteRevision = "rev-7",
        remoteModifiedAt = Instant.fromEpochSeconds(1_800_000_100, 456_000_000),
        remoteSize = 40,
    )

    @Test
    fun `load returns an empty index when the file is missing`() = runTest {
        assertEquals(SyncIndex(), JsonSyncStateStore(file).load())
    }

    @Test
    fun `saved index is restored by a new store instance`() = runTest {
        val index = SyncIndex().withRecord(record).withRecord(record.copy(relativePath = "b.md", remoteRevision = null))

        JsonSyncStateStore(file).save(index)

        assertEquals(index, JsonSyncStateStore(file).load())
    }

    @Test
    fun `save replaces the file atomically without leftovers`() = runTest {
        val store = JsonSyncStateStore(file)
        store.save(SyncIndex().withRecord(record))
        store.save(SyncIndex())

        assertEquals(SyncIndex(), JsonSyncStateStore(file).load())
        assertEquals(listOf("index.json"), file.parent.listDirectoryEntries().map { it.fileName.toString() })
    }

    @Test
    fun `corrupt index is reported instead of silently resetting`() = runTest {
        Files.createDirectories(file.parent)
        file.toFile().writeText("{ garbage")

        val failure = runCatching { JsonSyncStateStore(file).load() }

        assertEquals(true, failure.isFailure)
        assertEquals("{ garbage", file.readText())
    }
}
