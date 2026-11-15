package dev.mimir.core

import dev.mimir.core.sync.DefaultSyncEngine
import dev.mimir.core.sync.InMemorySyncStateStore
import dev.mimir.core.testing.InMemoryLocalVault
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncEngineUnresolvedFeedTest {
    private class UnresolvedFeedStorage(
        private val inner: InMemoryRemoteStorage,
    ) : ChangeFeedStorage by inner {
        var listAllCalls = 0
            private set

        override suspend fun listAll(
            root: RemotePath,
            onProgress: (folders: Int, files: Int) -> Unit,
        ): List<RemoteEntry> {
            listAllCalls++
            return inner.listAll(root, onProgress)
        }

        override suspend fun getChanges(sinceToken: String): RemoteChangesResult =
            RemoteChangesResult(emptyList(), "t2", unresolved = true)
    }

    @Test
    fun unresolvedFeedFallsBackToFullListing() = runTest {
        val inner = InMemoryRemoteStorage()
        inner.write(RemotePath("a.md"), Buffer().apply { writeString("hello") })
        val fake = UnresolvedFeedStorage(inner)
        val vault = InMemoryLocalVault()
        val store = InMemorySyncStateStore()
        val engine = DefaultSyncEngine(vault, fake, store)

        engine.syncNow()
        engine.syncNow()
        assertEquals(2, fake.listAllCalls)
    }
}
