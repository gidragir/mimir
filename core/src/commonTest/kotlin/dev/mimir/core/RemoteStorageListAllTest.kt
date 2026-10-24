package dev.mimir.core

import kotlinx.coroutines.test.runTest
import kotlinx.io.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class RemoteStorageListAllTest {

    private suspend fun RemoteStorage.recurse(dir: RemotePath, into: MutableList<RemoteEntry> = mutableListOf()): List<RemoteEntry> {
        for (entry in list(dir)) {
            if (entry.isDirectory) recurse(entry.path, into) else into.add(entry)
        }
        return into
    }

    @Test
    fun `default listAll returns only files of the whole tree like a recursion over list`() = runTest {
        val storage = InMemoryRemoteStorage()
        storage.write(RemotePath("top.md"), sourceOf(byteArrayOf(1)))
        storage.write(RemotePath("a/x.md"), sourceOf(byteArrayOf(2)))
        storage.write(RemotePath("a/b/c/deep.md"), sourceOf(byteArrayOf(3)))
        storage.write(RemotePath("d/e/other.md"), sourceOf(byteArrayOf(4)))

        val all = storage.listAll()

        assertEquals(storage.recurse(RemotePath("")), all)
        assertEquals(setOf("top.md", "a/x.md", "a/b/c/deep.md", "d/e/other.md"), all.map { it.path.value }.toSet())
        assertEquals(false, all.any { it.isDirectory })
    }

    @Test
    fun `default listAll respects root and reports progress per folder`() = runTest {
        val storage = InMemoryRemoteStorage()
        storage.write(RemotePath("a/x.md"), sourceOf(byteArrayOf(2)))
        storage.write(RemotePath("a/b/y.md"), sourceOf(byteArrayOf(3)))
        storage.write(RemotePath("z.md"), sourceOf(byteArrayOf(4)))

        val progress = mutableListOf<Pair<Int, Int>>()
        val all = storage.listAll(RemotePath("a")) { folders, files -> progress += folders to files }

        assertEquals(setOf("a/x.md", "a/b/y.md"), all.map { it.path.value }.toSet())
        assertEquals(2 to 2, progress.last())
        assertEquals(true, (1 to 0) in progress)
    }

    @Test
    fun `default listAll skips empty folders`() = runTest {
        val t = Instant.parse("2026-10-24T00:00:00Z")
        fun dir(p: String) = RemoteEntry(RemotePath(p), 0, t, null, isDirectory = true)
        fun file(p: String) = RemoteEntry(RemotePath(p), 1, t, "r", isDirectory = false)
        val tree = mapOf(
            "" to listOf(dir("empty"), dir("a"), file("r.md")),
            "empty" to emptyList(),
            "a" to listOf(dir("a/empty2"), file("a/f.md")),
            "a/empty2" to emptyList(),
        )
        val storage = object : RemoteStorage {
            override suspend fun list(dir: RemotePath) = tree.getValue(dir.value)
            override suspend fun stat(path: RemotePath): RemoteEntry? = null
            override suspend fun read(path: RemotePath): Source = error("unused")
            override suspend fun write(path: RemotePath, data: Source): RemoteEntry = error("unused")
            override suspend fun delete(path: RemotePath) = Unit
        }

        var lastProgress = 0 to 0
        val all = storage.listAll { folders, files -> lastProgress = folders to files }

        assertEquals(listOf("r.md", "a/f.md").toSet(), all.map { it.path.value }.toSet())
        assertEquals(4 to 2, lastProgress)
    }
}
