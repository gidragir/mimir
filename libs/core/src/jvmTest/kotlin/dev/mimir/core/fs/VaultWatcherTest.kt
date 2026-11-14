package dev.mimir.core.fs

import dev.mimir.core.VaultChange
import dev.mimir.core.testing.InMemoryLocalVault
import dev.mimir.core.testing.TestClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class VaultWatcherTest {
    private val root = Files.createTempDirectory("mimir-watch-test")
    private val scope = CoroutineScope(Dispatchers.Default)
    private val events = Channel<VaultChange>(Channel.UNLIMITED)

    @AfterTest
    fun cleanup() {
        scope.cancel()
        root.toFile().deleteRecursively()
    }

    private fun watcher(echoTtl: Long = 2000) =
        NioWatchServiceVaultWatcher(root, debounce = 150.milliseconds, echoTtl = echoTtl.milliseconds)

    private suspend fun NioWatchServiceVaultWatcher.collectInBackground() {
        scope.launch { start().collect { events.send(it) } }
        delay(300) // регистрация каталогов в WatchService
    }

    private suspend fun next(): VaultChange = withTimeout(5_000) { events.receive() }

    private suspend fun assertQuiet(millis: Long = 600) {
        assertNull(withTimeoutOrNull(millis) { events.receive() }, "unexpected event")
    }

    @Test
    fun `onWatching runs once the tree is registered, so nothing created after it is missed`() = runBlocking {
        val w = watcher()
        scope.launch {
            w.start(onWatching = { root.resolve("Notes").createDirectories().resolve("a.md").writeText("x") })
                .collect { events.send(it) }
        }

        val created = assertIs<VaultChange.Created>(next())
        assertEquals("Notes/a.md", created.entry.relativePath)
        w.stop()
    }

    @Test
    fun `reports created, modified and deleted files`() = runBlocking {
        val w = watcher().also { it.collectInBackground() }

        root.resolve("a.md").writeText("one")
        val created = assertIs<VaultChange.Created>(next())
        assertEquals("a.md", created.entry.relativePath)
        assertEquals(3L, created.entry.size)

        root.resolve("a.md").writeText("three")
        val modified = assertIs<VaultChange.Modified>(next())
        assertEquals("a.md", modified.entry.relativePath)

        root.resolve("a.md").deleteExisting()
        val deleted = assertIs<VaultChange.Deleted>(next())
        assertEquals("fs:a.md", deleted.id.value)
        w.stop()
    }

    @Test
    fun `burst of modifications yields a single Modified event`() = runBlocking {
        root.resolve("a.md").writeText("0")
        val w = watcher().also { it.collectInBackground() }

        repeat(5) {
            root.resolve("a.md").writeText("v$it")
            delay(20)
        }

        assertIs<VaultChange.Modified>(next())
        assertQuiet()
        w.stop()
    }

    @Test
    fun `files in newly created subdirectories are watched`() = runBlocking {
        val w = watcher().also { it.collectInBackground() }

        root.resolve("Sub").createDirectories()
        delay(300)
        root.resolve("Sub/x.md").writeText("x")

        val change = next()
        assertEquals("fs:Sub/x.md", change.id.value)
        w.stop()
    }

    @Test
    fun `service paths are ignored`() = runBlocking {
        root.resolve(".git").createDirectories()
        val w = watcher().also { it.collectInBackground() }

        root.resolve(".git/HEAD").writeText("ref")
        root.resolve("note.tmp").writeText("x")

        assertQuiet()
        w.stop()
    }

    @Test
    fun `writes marked as sync writes do not echo back until the ttl expires`() = runBlocking {
        root.resolve("a.md").writeText("0")
        val w = watcher(echoTtl = 800).also { it.collectInBackground() }

        w.markWrittenBySync("a.md")
        root.resolve("a.md").writeText("from cloud")
        assertQuiet(500)

        delay(800)
        root.resolve("a.md").writeText("user edit")
        assertEquals("fs:a.md", next().id.value)
        w.stop()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `polling watcher diffs snapshots and honours sync marks`() = runTest {
        val vault = InMemoryLocalVault()
        val clock = TestClock()
        val polled = Channel<VaultChange>(Channel.UNLIMITED)
        val poller = PollingVaultWatcher(
            scan = vault::list,
            interval = 50.milliseconds,
            echoTtl = 300.milliseconds,
            clock = clock,
        )
        backgroundScope.launch { poller.start().collect { polled.send(it) } }
        runCurrent()

        vault.write("a.md", Buffer().also { it.write("1".encodeToByteArray()) })
        advanceTimeBy(50.milliseconds)
        runCurrent()
        assertIs<VaultChange.Created>(polled.receive())

        clock.advanceBy(5.milliseconds)
        vault.write("a.md", Buffer().also { it.write("22".encodeToByteArray()) })
        advanceTimeBy(50.milliseconds)
        runCurrent()
        assertIs<VaultChange.Modified>(polled.receive())

        poller.markWrittenBySync("a.md")
        clock.advanceBy(5.milliseconds)
        vault.write("a.md", Buffer().also { it.write("333".encodeToByteArray()) })
        advanceTimeBy(200.milliseconds)
        runCurrent()
        assertTrue(polled.tryReceive().isFailure, "expected no event during echoTtl")

        clock.advanceBy(400.milliseconds)
        vault.deleteByPath("a.md")
        advanceTimeBy(50.milliseconds)
        runCurrent()
        assertIs<VaultChange.Deleted>(polled.receive())
        poller.stop()
    }
}
