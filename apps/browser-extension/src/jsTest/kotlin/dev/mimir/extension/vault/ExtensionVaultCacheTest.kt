package dev.mimir.extension.vault

import dev.mimir.core.ChangeFeedStorage
import dev.mimir.core.ChangePeek
import dev.mimir.core.RemoteChangesResult
import dev.mimir.core.RemoteEntry
import dev.mimir.core.RemotePath
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ExtensionVaultCacheTest {

    private class FakeFeedStorage(
        private val peek: ChangePeek,
        private val latestToken: String = peek.nextToken,
    ) : ChangeFeedStorage {
        var peekCalls = 0
        var getChangesCalls = 0

        override suspend fun getLatestChangeToken(): String = latestToken

        override suspend fun getChanges(sinceToken: String): RemoteChangesResult {
            getChangesCalls++
            return RemoteChangesResult(changes = emptyList(), nextToken = latestToken)
        }

        override suspend fun peekChanges(sinceToken: String): ChangePeek {
            peekCalls++
            return peek
        }

        override suspend fun list(dir: RemotePath): List<RemoteEntry> = emptyList()
        override suspend fun listAll(root: RemotePath, onProgress: (folders: Int, files: Int) -> Unit): List<RemoteEntry> = emptyList()
        override suspend fun stat(path: RemotePath): RemoteEntry? = null
        override suspend fun read(path: RemotePath): Source = Buffer()
        override suspend fun write(path: RemotePath, data: Source): RemoteEntry =
            RemoteEntry(path, 0, Instant.fromEpochMilliseconds(0), "r1", false)
        override suspend fun delete(path: RemotePath) {}
    }

    @Test
    fun removalOnlyFeedForcesFullList() = runTest {
        val feed = FakeFeedStorage(peek = ChangePeek(hasChanges = true, nextToken = "t2"))
        val check = checkChangeFeed(feed, cachedToken = "t1")
        assertEquals(IncrementalSyncCheck.NeedsFullList("t2"), check)
        assertEquals(1, feed.peekCalls)
        assertEquals(0, feed.getChangesCalls)
    }

    @Test
    fun unchangedFeedReturnsUnchangedWithNextToken() = runTest {
        val feed = FakeFeedStorage(peek = ChangePeek(hasChanges = false, nextToken = "t2"))
        val check = checkChangeFeed(feed, cachedToken = "t1")
        assertEquals(IncrementalSyncCheck.Unchanged("t2"), check)
        assertEquals(1, feed.peekCalls)
        assertEquals(0, feed.getChangesCalls)
    }
}
