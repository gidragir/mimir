package dev.mimir.obsidian.vault

import dev.mimir.core.VaultChange
import dev.mimir.core.VaultFileId
import dev.mimir.core.fs.EchoFilter
import dev.mimir.core.testing.TestClock
import dev.mimir.obsidian.fakes.FakeVaultFs
import dev.mimir.obsidian.platform.FsStat
import dev.mimir.obsidian.platform.ResourceStreamFn
import dev.mimir.obsidian.platform.VaultEvent
import dev.mimir.obsidian.platform.VaultFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private class CountingVaultFs(private val delegate: FakeVaultFs) : VaultFs by delegate {
    var statCalls = 0
        private set

    override suspend fun stat(path: String): FsStat? {
        statCalls++
        return delegate.stat(path)
    }
}

class ObsidianLocalVaultTest {

    private val testClock = TestClock(Instant.fromEpochMilliseconds(1000L))
    private val echo = EchoFilter(ttl = 5.seconds, clock = testClock)

    @Test
    fun writeCallsStatOnce() = runTest {
        val delegate = FakeVaultFs()
        val fs = CountingVaultFs(delegate)
        val vault = ObsidianLocalVault(
            fs = fs,
            resources = { Buffer() },
            configDir = ".obsidian",
            events = MutableSharedFlow(),
            echo = echo,
            clock = testClock,
        )

        val entry = vault.write("note.md", Buffer().apply { write("hello".encodeToByteArray()) })
        assertEquals("note.md", entry.relativePath)
        assertEquals(5L, entry.size)
        assertEquals(1, fs.statCalls)
    }

    @Test
    fun listBatchesStatAcrossMoreThan64Files() = runTest {
        val fs = FakeVaultFs()
        fs.mkdir("notes")
        for (i in 0 until 130) {
            fs.writeBinary("notes/n-$i.md", "body-$i".encodeToByteArray())
        }
        val vault = ObsidianLocalVault(
            fs = fs,
            resources = { Buffer() },
            configDir = ".obsidian",
            events = MutableSharedFlow(),
            echo = echo,
            clock = testClock,
        )

        val entries = vault.list()
        assertEquals(130, entries.size)
    }

    @Test
    fun listsFilesRecursivelyWithSizeAndMtime() = runTest {
        val fs = FakeVaultFs()
        fs.mkdir("folder")
        fs.mkdir("folder/sub")
        fs.writeBinary("root.md", "root content".encodeToByteArray())
        fs.writeBinary("folder/sub/nested.md", "nested content".encodeToByteArray())

        val vault = ObsidianLocalVault(
            fs = fs,
            resources = { Buffer() },
            configDir = ".obsidian",
            events = MutableSharedFlow(),
            echo = echo,
            clock = testClock,
        )

        val entries = vault.list().associateBy { it.relativePath }
        assertEquals(2, entries.size)

        val root = entries["root.md"]!!
        assertEquals("root.md", root.relativePath)
        assertEquals(VaultFileId("root.md"), root.id)
        assertEquals("root content".encodeToByteArray().size.toLong(), root.size)

        val nested = entries["folder/sub/nested.md"]!!
        assertEquals("folder/sub/nested.md", nested.relativePath)
        assertEquals(VaultFileId("folder/sub/nested.md"), nested.id)
    }

    @Test
    fun excludesConfigDirAndTmpFiles() = runTest {
        val fs = FakeVaultFs()
        fs.mkdir(".obsidian")
        fs.mkdir("a")
        fs.writeBinary(".obsidian/app.json", "{}".encodeToByteArray())
        fs.writeBinary("a/.b.md.mimir-tmp", "tmp".encodeToByteArray())
        fs.writeBinary("a/b.md", "real".encodeToByteArray())

        val vault = ObsidianLocalVault(
            fs = fs,
            resources = { Buffer() },
            configDir = ".obsidian",
            events = MutableSharedFlow(),
            echo = echo,
            clock = testClock,
        )

        val list = vault.list()
        assertEquals(1, list.size)
        assertEquals("a/b.md", list.single().relativePath)
        assertEquals(setOf(".obsidian"), vault.hiddenPaths())
    }

    @Test
    fun writeGoesThroughTmpAndRename() = runTest {
        val fs = FakeVaultFs()
        val vault = ObsidianLocalVault(
            fs = fs,
            resources = { Buffer() },
            configDir = ".obsidian",
            events = MutableSharedFlow(),
            echo = echo,
            clock = testClock,
            writeChunkBytes = 1 shl 20, // 1 MiB chunks
        )

        val dataSize = (2.5 * 1024 * 1024).toInt() // 2.5 MiB
        val data = ByteArray(dataSize) { (it % 127).toByte() }

        val buffer = Buffer().apply { write(data) }
        val entry = vault.write("a/b.md", buffer)

        assertEquals("a/b.md", entry.relativePath)
        assertEquals(dataSize.toLong(), entry.size)
        assertContentEquals(data, fs.files["a/b.md"])
        assertFalse(fs.exists("a/.b.md.mimir-tmp"))

        // Verify call log contains mkdir, writeBinary, appendBinary x2, rename
        assertTrue(fs.callLog.contains("mkdir(a)"))
        assertTrue(fs.callLog.contains("writeBinary(a/.b.md.mimir-tmp, 1048576)"))
        assertTrue(fs.callLog.contains("appendBinary(a/.b.md.mimir-tmp, 1048576)"))
        assertTrue(fs.callLog.contains("appendBinary(a/.b.md.mimir-tmp, 524288)"))
        assertTrue(fs.callLog.contains("rename(a/.b.md.mimir-tmp, a/b.md)"))
    }

    @Test
    fun writeCancelledLeavesTargetIntact() = runTest {
        val fs = FakeVaultFs()
        fs.mkdir("a")
        fs.writeBinary("a/b.md", "old content".encodeToByteArray())

        fs.failOnNthAppendBinary = 2
        fs.failWithCancellation = true

        val vault = ObsidianLocalVault(
            fs = fs,
            resources = { Buffer() },
            configDir = ".obsidian",
            events = MutableSharedFlow(),
            echo = echo,
            clock = testClock,
            writeChunkBytes = 1 shl 20,
        )

        val dataSize = (2.5 * 1024 * 1024).toInt()
        val data = ByteArray(dataSize) { 42 }

        assertFailsWith<CancellationException> {
            vault.write("a/b.md", Buffer().apply { write(data) })
        }

        // a/b.md still has old content
        assertEquals("old content", fs.readBinary("a/b.md").decodeToString())
        // temp file removed
        assertFalse(fs.exists("a/.b.md.mimir-tmp"))
    }

    @Test
    fun writeFailureRemovesTmpAndRethrows() = runTest {
        val fs = FakeVaultFs()
        fs.failOnNthAppendBinary = 1
        fs.failWithCancellation = false

        val vault = ObsidianLocalVault(
            fs = fs,
            resources = { Buffer() },
            configDir = ".obsidian",
            events = MutableSharedFlow(),
            echo = echo,
            clock = testClock,
            writeChunkBytes = 1 shl 20,
        )

        val data = ByteArray(2 * 1024 * 1024)
        assertFailsWith<IllegalStateException> {
            vault.write("a/b.md", Buffer().apply { write(data) })
        }

        assertFalse(fs.exists("a/.b.md.mimir-tmp"))
    }

    @Test
    fun writeReplacesExisting() = runTest {
        val fs = FakeVaultFs()
        fs.mkdir("a")
        fs.writeBinary("a/b.md", "old".encodeToByteArray())

        val vault = ObsidianLocalVault(
            fs = fs,
            resources = { Buffer() },
            configDir = ".obsidian",
            events = MutableSharedFlow(),
            echo = echo,
            clock = testClock,
        )

        vault.write("a/b.md", Buffer().apply { write("new".encodeToByteArray()) })

        assertEquals("new", fs.readBinary("a/b.md").decodeToString())
        val removeIndex = fs.callLog.lastIndexOf("remove(a/b.md)")
        val renameIndex = fs.callLog.lastIndexOf("rename(a/.b.md.mimir-tmp, a/b.md)")
        assertTrue(removeIndex in 0 until renameIndex, "remove target must happen before rename")
    }

    @Test
    fun readStreamsViaResourceFn() = runTest {
        val fs = FakeVaultFs()
        fs.writeBinary("notes.md", "content".encodeToByteArray())

        var resourceCalledFor: String? = null
        val resources = ResourceStreamFn { path ->
            resourceCalledFor = path
            Buffer().apply { write("from resource".encodeToByteArray()) }
        }

        val vault = ObsidianLocalVault(
            fs = fs,
            resources = resources,
            configDir = ".obsidian",
            events = MutableSharedFlow(),
            echo = echo,
            clock = testClock,
        )

        val stream = vault.read(VaultFileId("notes.md"))
        assertEquals("from resource", stream.readByteArray().decodeToString())
        assertEquals("notes.md", resourceCalledFor)
        assertFalse(fs.callLog.contains("readBinary(notes.md)"))
    }

    @Test
    fun unicodeAndSpacesRoundTrip() = runTest {
        val fs = FakeVaultFs()
        val path = "Заметки/Идея #1.md"

        val vault = ObsidianLocalVault(
            fs = fs,
            resources = { p -> Buffer().apply { write(fs.readBinary(p)) } },
            configDir = ".obsidian",
            events = MutableSharedFlow(),
            echo = echo,
            clock = testClock,
        )

        val data = "Привет мир".encodeToByteArray()
        vault.write(path, Buffer().apply { write(data) })

        val entry = vault.getEntry(path)
        assertEquals(path, entry?.relativePath)

        val readBack = vault.read(VaultFileId(path)).readByteArray()
        assertContentEquals(data, readBack)

        val list = vault.list()
        assertEquals(1, list.size)
        assertEquals(path, list.single().relativePath)
    }

    @Test
    fun deleteRemovesFile() = runTest {
        val fs = FakeVaultFs()
        fs.writeBinary("doc.md", "text".encodeToByteArray())

        val vault = ObsidianLocalVault(
            fs = fs,
            resources = { Buffer() },
            configDir = ".obsidian",
            events = MutableSharedFlow(),
            echo = echo,
            clock = testClock,
        )

        vault.deleteByPath("doc.md")
        assertFalse(fs.exists("doc.md"))
        assertTrue(echo.isRecent("doc.md"))
    }

    @Test
    fun deleteByPathMissingIsNoop() = runTest {
        val fs = FakeVaultFs()
        val vault = ObsidianLocalVault(
            fs = fs,
            resources = { Buffer() },
            configDir = ".obsidian",
            events = MutableSharedFlow(),
            echo = echo,
            clock = testClock,
        )

        vault.deleteByPath("nonexistent.md") // does not throw
    }

    @Test
    fun changesMapsEventsAndSkipsEcho() = runTest {
        val fs = FakeVaultFs()
        val eventFlow = MutableSharedFlow<VaultEvent>(replay = 10)

        val vault = ObsidianLocalVault(
            fs = fs,
            resources = { Buffer() },
            configDir = ".obsidian",
            events = eventFlow,
            echo = echo,
            clock = testClock,
        )

        fs.writeBinary("real.md", "data".encodeToByteArray())
        fs.writeBinary("renamed.md", "renamed data".encodeToByteArray())

        // 1. Mark echo for real.md
        echo.mark("echo.md")

        val received = mutableListOf<VaultChange>()
        val job = launch {
            vault.changes().take(3).toList(received)
        }

        // Echo path -> skipped
        eventFlow.emit(VaultEvent.Upsert("echo.md"))
        // Config dir path -> skipped
        eventFlow.emit(VaultEvent.Upsert(".obsidian/config.json"))
        // Tmp path -> skipped
        eventFlow.emit(VaultEvent.Upsert(".mimir-tmp"))

        // Real event 1: Upsert
        eventFlow.emit(VaultEvent.Upsert("real.md"))

        // Real event 2 & 3: Renamed
        eventFlow.emit(VaultEvent.Renamed("old.md", "renamed.md"))

        job.join()

        assertEquals(3, received.size)
        assertIs<VaultChange.Modified>(received[0])
        assertEquals("real.md", received[0].id.value)

        assertIs<VaultChange.Deleted>(received[1])
        assertEquals("old.md", received[1].id.value)

        assertIs<VaultChange.Modified>(received[2])
        assertEquals("renamed.md", received[2].id.value)
    }
}
