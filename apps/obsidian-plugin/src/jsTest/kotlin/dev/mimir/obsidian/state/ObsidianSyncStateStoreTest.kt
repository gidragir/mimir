package dev.mimir.obsidian.state

import dev.mimir.core.RemotePath
import dev.mimir.core.VaultFileId
import dev.mimir.core.sync.FileSyncRecord
import dev.mimir.core.sync.SyncIndex
import dev.mimir.obsidian.fakes.FakeVaultFs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ObsidianSyncStateStoreTest {

    private val pluginDir = ".obsidian/plugins/mimir-sync"

    @Test
    fun missingFileGivesEmptyIndex() = runTest {
        val fs = FakeVaultFs()
        val store = ObsidianSyncStateStore(fs, pluginDir)

        val index = store.load()
        assertEquals(SyncIndex(), index)
    }

    @Test
    fun saveThenLoadRoundTrip() = runTest {
        val fs = FakeVaultFs()
        val store = ObsidianSyncStateStore(fs, pluginDir)

        val record = FileSyncRecord(
            relativePath = "note.md",
            localId = VaultFileId("local-1"),
            baseHash = "hash123",
            localSize = 50L,
            localModifiedAt = Instant.fromEpochSeconds(1700000000L),
            remotePath = RemotePath("remote/note.md"),
            remoteRevision = "rev-1",
            remoteModifiedAt = Instant.fromEpochSeconds(1700000010L),
            remoteSize = 48L,
        )
        val index = SyncIndex(version = 1, records = mapOf(record.relativePath to record))

        store.save(index)
        val loaded = store.load()

        assertEquals(index.version, loaded.version)
        assertEquals(1, loaded.records.size)
        assertEquals(record, loaded.records["note.md"])
    }

    @Test
    fun saveIsAtomic() = runTest {
        val fs = FakeVaultFs()
        val store = ObsidianSyncStateStore(fs, pluginDir)

        val index = SyncIndex()
        store.save(index)

        // Verify call log contains writeBinary to tmp, remove index.json, rename tmp to index.json
        val writeTmp = fs.callLog.indexOfFirst { it.startsWith("writeBinary($pluginDir/index.json.tmp") }
        val rename = fs.callLog.indexOfFirst { it == "rename($pluginDir/index.json.tmp, $pluginDir/index.json)" }

        assertTrue(writeTmp >= 0, "must write to index.json.tmp")
        assertTrue(rename > writeTmp, "must rename tmp to index.json after writing")
        assertFalse(fs.exists("$pluginDir/index.json.tmp"))
        assertTrue(fs.exists("$pluginDir/index.json"))
    }

    @Test
    fun corruptJsonGivesEmptyIndexAndKeepsBackup() = runTest {
        val fs = FakeVaultFs()
        fs.mkdir(".obsidian")
        fs.mkdir(".obsidian/plugins")
        fs.mkdir(pluginDir)
        fs.writeBinary("$pluginDir/index.json", "{ corrupt json content".encodeToByteArray())

        val store = ObsidianSyncStateStore(fs, pluginDir)
        val loaded = store.load()

        assertEquals(SyncIndex(), loaded)
        assertFalse(fs.exists("$pluginDir/index.json"))
        assertTrue(fs.exists("$pluginDir/index.json.corrupt"))
        assertEquals("{ corrupt json content", fs.readBinary("$pluginDir/index.json.corrupt").decodeToString())
    }
}
