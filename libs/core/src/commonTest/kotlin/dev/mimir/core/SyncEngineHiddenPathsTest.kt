package dev.mimir.core

import dev.mimir.core.sync.DefaultSyncEngine
import dev.mimir.core.sync.InMemorySyncStateStore
import dev.mimir.core.testing.InMemoryLocalVault
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Пути, которые vault не показывает (например, за символьной ссылкой), не считаются удалёнными. */
class SyncEngineHiddenPathsTest {

    private fun bufferOf(text: String): Buffer =
        Buffer().also { it.write(text.encodeToByteArray()) }

    /** Обёртка над [InMemoryLocalVault]: прячет пути под [hidden], как vault прячет символьные ссылки. */
    private class HidingVault(val delegate: InMemoryLocalVault = InMemoryLocalVault()) : LocalVault by delegate {
        val hidden = mutableSetOf<String>()

        private fun isHidden(path: String) = hidden.any { path == it || path.startsWith("$it/") }

        override suspend fun list(): List<LocalEntry> = delegate.list().filterNot { isHidden(it.relativePath) }

        override suspend fun getEntry(relativePath: String): LocalEntry? =
            if (isHidden(relativePath)) null else delegate.getEntry(relativePath)

        override suspend fun hiddenPaths(): Set<String> = hidden.toSet()
    }

    private class Fixture {
        val vault = HidingVault()
        val remote = InMemoryRemoteStorage()
        val store = InMemorySyncStateStore()
        val engine = DefaultSyncEngine(vault, remote, store)
    }

    @Test
    fun syncedFileUnderHiddenPathIsNotDeletedRemotely() = runTest {
        val f = Fixture()
        f.vault.write("link/note.md", bufferOf("N"))
        f.vault.write("a.md", bufferOf("A"))
        f.engine.syncNow()
        assertEquals(setOf("a.md", "link/note.md"), f.store.load().records.keys)

        f.vault.hidden += "link"
        f.engine.syncNow()

        assertNotNull(f.remote.stat(RemotePath("link/note.md")))
        assertTrue("link/note.md" in f.store.load().records.keys)
    }

    @Test
    fun remoteFileUnderHiddenPathIsNotDownloaded() = runTest {
        val f = Fixture()
        f.vault.hidden += "link"
        f.remote.write(RemotePath("link/new.md"), bufferOf("R"))
        f.remote.write(RemotePath("b.md"), bufferOf("B"))

        f.engine.syncNow()

        assertEquals(setOf("b.md"), f.store.load().records.keys)
        assertEquals(listOf("b.md"), f.vault.delegate.list().map { it.relativePath })
        assertEquals(SyncStatus.Idle::class, f.engine.status.value::class)
    }
}
