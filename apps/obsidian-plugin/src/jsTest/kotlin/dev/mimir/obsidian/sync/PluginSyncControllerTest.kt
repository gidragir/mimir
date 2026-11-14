package dev.mimir.obsidian.sync

import dev.mimir.core.ChangeFeedStorage
import dev.mimir.core.RemoteChangesResult
import dev.mimir.core.RemoteEntry
import dev.mimir.core.RemotePath
import dev.mimir.core.RemoteUnavailableException
import dev.mimir.core.SyncAbortedException
import dev.mimir.core.SyncEngine
import dev.mimir.core.SyncItemAction
import dev.mimir.core.testing.FakeSyncEngine
import dev.mimir.core.testing.TestClock
import dev.mimir.drive.client.DriveAuthException
import dev.mimir.obsidian.daemon.DaemonPresence
import dev.mimir.obsidian.daemon.DaemonProbe
import dev.mimir.obsidian.fakes.FakeLocalStorage
import dev.mimir.obsidian.platform.UrlResponse
import dev.mimir.obsidian.platform.VaultEvent
import dev.mimir.obsidian.state.DeviceSecretStore
import dev.mimir.obsidian.state.PluginSettings
import dev.mimir.obsidian.state.PluginSettingsStore
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PluginSyncControllerTest {

    private class FakeChangeFeed(
        var latestToken: String = "token-1",
        var changes: List<dev.mimir.core.RemoteChange> = emptyList(),
    ) : ChangeFeedStorage {
        var getChangesCalls = 0
        override suspend fun getLatestChangeToken(): String = latestToken
        override suspend fun getChanges(sinceToken: String): RemoteChangesResult {
            getChangesCalls++
            return RemoteChangesResult(changes = changes, nextToken = latestToken)
        }

        override suspend fun list(dir: RemotePath): List<RemoteEntry> = emptyList()
        override suspend fun listAll(root: RemotePath, onProgress: (folders: Int, files: Int) -> Unit): List<RemoteEntry> = emptyList()
        override suspend fun stat(path: RemotePath): RemoteEntry? = null
        override suspend fun read(path: RemotePath): Source = Buffer()
        override suspend fun write(path: RemotePath, data: Source): RemoteEntry =
            RemoteEntry(
                path = path,
                size = 0,
                modifiedAt = Instant.fromEpochMilliseconds(0),
                revision = "rev-1",
                isDirectory = false,
            )
        override suspend fun delete(path: RemotePath) {}
    }

    private class FakeEngineSource(
        var pair: Pair<SyncEngine, ChangeFeedStorage>? = null,
    ) : EngineSource {
        override suspend fun buildEngine(): Pair<SyncEngine, ChangeFeedStorage>? = pair
    }

    private fun testSettingsStore(initial: PluginSettings = PluginSettings(rootFolder = "V")): PluginSettingsStore {
        var raw: dynamic = null
        val store = PluginSettingsStore(load = { raw }, save = { raw = it })
        return store
    }

    private fun fakeProbe(status: DaemonPresence): DaemonProbe = DaemonProbe(
        request = { req ->
            when (status) {
                DaemonPresence.Unauthorized -> UrlResponse(401, emptyMap(), ByteArray(0))
                DaemonPresence.ServesThisVault -> UrlResponse(200, emptyMap(), """{"vaultPath":"/vault"}""".encodeToByteArray())
                DaemonPresence.ServesOtherVault -> UrlResponse(200, emptyMap(), """{"vaultPath":"/other"}""".encodeToByteArray())
                DaemonPresence.Absent -> UrlResponse(500, emptyMap(), ByteArray(0))
            }
        },
        port = 47821,
        isDesktop = true,
        vaultBasePath = "/vault",
    )

    @Test
    fun startupRunsOnce() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(testDispatcher)
        val clock = TestClock()

        val engine = FakeSyncEngine()
        val feed = FakeChangeFeed()
        val source = FakeEngineSource(engine to feed)
        val secrets = DeviceSecretStore(FakeLocalStorage())
        val settings = testSettingsStore()
        settings.update { it.copy(rootFolder = "V") }

        val vaultEvents = MutableSharedFlow<VaultEvent>()
        val controller = PluginSyncController(
            scope = scope,
            assembler = source,
            probe = fakeProbe(DaemonPresence.Absent),
            secrets = secrets,
            settings = settings,
            vaultEvents = vaultEvents,
            clock = clock,
        )

        try {
            controller.start()
            advanceTimeBy(100)
            assertEquals(1, engine.syncNowCalls)
            assertIs<PluginState.UpToDate>(controller.state.value)
        } finally {
            controller.stop()
            scope.cancel()
        }
    }

    @Test
    fun burstOfVaultEventsCoalescesIntoOneRun() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(testDispatcher)
        val clock = TestClock()

        val engine = FakeSyncEngine()
        val feed = FakeChangeFeed()
        val source = FakeEngineSource(engine to feed)
        val secrets = DeviceSecretStore(FakeLocalStorage())
        val settings = testSettingsStore()
        settings.update { it.copy(rootFolder = "V") }

        val vaultEvents = MutableSharedFlow<VaultEvent>(extraBufferCapacity = 200)
        val controller = PluginSyncController(
            scope = scope,
            assembler = source,
            probe = fakeProbe(DaemonPresence.Absent),
            secrets = secrets,
            settings = settings,
            vaultEvents = vaultEvents,
            clock = clock,
        )

        try {
            controller.start()
            advanceTimeBy(100) // Startup run finishes
            assertEquals(1, engine.syncNowCalls)

            // Burst of 100 events across 3 seconds
            for (i in 0 until 100) {
                vaultEvents.emit(VaultEvent.Upsert("note-$i.md"))
                clock.advanceBy(30.seconds / 1000)
                advanceTimeBy(30)
            }

            // Within 3 seconds of bursting, no new sync has run yet
            assertEquals(1, engine.syncNowCalls)

            // Advance 4.9 seconds (less than 5s quiet period)
            clock.advanceBy(4900.seconds / 1000)
            advanceTimeBy(4_900)
            assertEquals(1, engine.syncNowCalls)

            // Advance 200ms more (5s quiet period reached)
            clock.advanceBy(200.seconds / 1000)
            advanceTimeBy(200)
            assertEquals(2, engine.syncNowCalls)
        } finally {
            controller.stop()
            scope.cancel()
        }
    }

    @Test
    fun triggerDuringRunCausesExactlyOneRerun() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(testDispatcher)
        val clock = TestClock()

        val syncGate = CompletableDeferred<Unit>()
        val engine = FakeSyncEngine(onSync = {
            syncGate.await()
        })
        val source = FakeEngineSource(engine to FakeChangeFeed())
        val secrets = DeviceSecretStore(FakeLocalStorage())
        val settings = testSettingsStore()
        settings.update { it.copy(rootFolder = "V") }

        val vaultEvents = MutableSharedFlow<VaultEvent>()
        val controller = PluginSyncController(
            scope = scope,
            assembler = source,
            probe = fakeProbe(DaemonPresence.Absent),
            secrets = secrets,
            settings = settings,
            vaultEvents = vaultEvents,
            clock = clock,
        )

        try {
            controller.start()
            advanceTimeBy(50)
            // Active run 1 is hanging on syncGate
            assertEquals(1, engine.syncNowCalls)

            // Trigger manual runs while run 1 is active
            controller.trigger(Trigger.Manual)
            controller.trigger(Trigger.Manual)
            controller.trigger(Trigger.Manual)

            // Still only run 1 started
            assertEquals(1, engine.syncNowCalls)

            // Release run 1
            syncGate.complete(Unit)
            advanceTimeBy(50)

            // Exactly one rerun took place
            assertEquals(2, engine.syncNowCalls)
        } finally {
            controller.stop()
            scope.cancel()
        }
    }

    @Test
    fun resumeThrottledTo30s() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(testDispatcher)
        val clock = TestClock()

        val engine = FakeSyncEngine()
        val remoteEntry = RemoteEntry(
            path = RemotePath("test.md"),
            size = 10,
            modifiedAt = Instant.fromEpochMilliseconds(1000),
            revision = "r1",
            isDirectory = false,
        )
        val change = dev.mimir.core.RemoteChange(RemotePath("test.md"), isDeleted = false, entry = remoteEntry)
        val feed = FakeChangeFeed(changes = listOf(change))
        val source = FakeEngineSource(engine to feed)
        val secrets = DeviceSecretStore(FakeLocalStorage())
        val settings = testSettingsStore()
        settings.update { it.copy(rootFolder = "V") }

        val vaultEvents = MutableSharedFlow<VaultEvent>()
        val controller = PluginSyncController(
            scope = scope,
            assembler = source,
            probe = fakeProbe(DaemonPresence.Absent),
            secrets = secrets,
            settings = settings,
            vaultEvents = vaultEvents,
            clock = clock,
        )

        try {
            controller.start()
            advanceTimeBy(100)
            assertEquals(1, engine.syncNowCalls)

            // First resume at t=10s
            clock.advanceBy(10.seconds)
            advanceTimeBy(10_000)
            controller.trigger(Trigger.Resume)
            advanceTimeBy(50)
            assertEquals(2, engine.syncNowCalls)

            // Second resume at t=20s (only 10s after first resume -> throttled!)
            clock.advanceBy(10.seconds)
            advanceTimeBy(10_000)
            controller.trigger(Trigger.Resume)
            advanceTimeBy(50)
            assertEquals(2, engine.syncNowCalls)

            // Third resume at t=41s (>30s after first resume -> executed!)
            clock.advanceBy(21.seconds)
            advanceTimeBy(21_000)
            controller.trigger(Trigger.Resume)
            advanceTimeBy(50)
            assertEquals(3, engine.syncNowCalls)
        } finally {
            controller.stop()
            scope.cancel()
        }
    }

    @Test
    fun timerSkipsWhenNoRemoteChanges() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(testDispatcher)
        val clock = TestClock()

        val engine = FakeSyncEngine()
        val feed = FakeChangeFeed(latestToken = "tok-1", changes = emptyList())
        val source = FakeEngineSource(engine to feed)
        val secrets = DeviceSecretStore(FakeLocalStorage())
        secrets.saveChangeToken("tok-1")
        val settings = testSettingsStore()
        settings.update { it.copy(rootFolder = "V") }

        val controller = PluginSyncController(
            scope = scope,
            assembler = source,
            probe = fakeProbe(DaemonPresence.Absent),
            secrets = secrets,
            settings = settings,
            vaultEvents = MutableSharedFlow(),
            clock = clock,
        )

        try {
            controller.start()
            advanceTimeBy(100)
            assertEquals(1, engine.syncNowCalls) // Startup run

            // Advance 5 minutes to trigger timer (no remote changes)
            clock.advanceBy(5.minutes)
            advanceTimeBy(5.minutes.inWholeMilliseconds)
            assertEquals(1, engine.syncNowCalls) // Skipped!
            assertTrue(feed.getChangesCalls >= 1)
        } finally {
            controller.stop()
            scope.cancel()
        }
    }

    @Test
    fun timerRunsWhenRemoteChanged() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(testDispatcher)
        val clock = TestClock()

        val engine = FakeSyncEngine()
        val remoteEntry = RemoteEntry(
            path = RemotePath("test.md"),
            size = 10,
            modifiedAt = Instant.fromEpochMilliseconds(1000),
            revision = "r1",
            isDirectory = false,
        )
        val change = dev.mimir.core.RemoteChange(RemotePath("test.md"), isDeleted = false, entry = remoteEntry)
        val feed = FakeChangeFeed(latestToken = "tok-2", changes = listOf(change))
        val source = FakeEngineSource(engine to feed)
        val secrets = DeviceSecretStore(FakeLocalStorage())
        secrets.saveChangeToken("tok-1")
        val settings = testSettingsStore()
        settings.update { it.copy(rootFolder = "V") }

        val controller = PluginSyncController(
            scope = scope,
            assembler = source,
            probe = fakeProbe(DaemonPresence.Absent),
            secrets = secrets,
            settings = settings,
            vaultEvents = MutableSharedFlow(),
            clock = clock,
        )

        try {
            controller.start()
            advanceTimeBy(100)
            assertEquals(1, engine.syncNowCalls) // Startup

            clock.advanceBy(5.minutes)
            advanceTimeBy(5.minutes.inWholeMilliseconds)
            assertEquals(2, engine.syncNowCalls) // Ran because changes were found
        } finally {
            controller.stop()
            scope.cancel()
        }
    }

    @Test
    fun timerStopsWhenHidden() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(testDispatcher)
        val clock = TestClock()

        val engine = FakeSyncEngine()
        val remoteEntry = RemoteEntry(
            path = RemotePath("test.md"),
            size = 10,
            modifiedAt = Instant.fromEpochMilliseconds(1000),
            revision = "r1",
            isDirectory = false,
        )
        val change = dev.mimir.core.RemoteChange(RemotePath("test.md"), isDeleted = false, entry = remoteEntry)
        val feed = FakeChangeFeed(latestToken = "tok-2", changes = listOf(change))
        val source = FakeEngineSource(engine to feed)
        val secrets = DeviceSecretStore(FakeLocalStorage())
        val settings = testSettingsStore()
        settings.update { it.copy(rootFolder = "V") }

        val controller = PluginSyncController(
            scope = scope,
            assembler = source,
            probe = fakeProbe(DaemonPresence.Absent),
            secrets = secrets,
            settings = settings,
            vaultEvents = MutableSharedFlow(),
            clock = clock,
        )

        try {
            controller.start()
            advanceTimeBy(100)
            assertEquals(1, engine.syncNowCalls)

            controller.onVisibilityChanged(false)
            clock.advanceBy(15.minutes)
            advanceTimeBy(15.minutes.inWholeMilliseconds)
            // No timer executions when hidden
            assertEquals(1, engine.syncNowCalls)
        } finally {
            controller.stop()
            scope.cancel()
        }
    }

    @Test
    fun hiddenCancelsAndCleansTmp() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(testDispatcher)
        val clock = TestClock()

        val gate = CompletableDeferred<Unit>()
        val delegate = FakeSyncEngine(onSync = {
            gate.await()
        })
        var stopCount = 0
        val engine = object : SyncEngine by delegate {
            override suspend fun stop() {
                stopCount++
                delegate.stop()
            }
        }
        val source = FakeEngineSource(engine to FakeChangeFeed())
        val secrets = DeviceSecretStore(FakeLocalStorage())
        val settings = testSettingsStore()
        settings.update { it.copy(rootFolder = "V") }

        val controller = PluginSyncController(
            scope = scope,
            assembler = source,
            probe = fakeProbe(DaemonPresence.Absent),
            secrets = secrets,
            settings = settings,
            vaultEvents = MutableSharedFlow(),
            clock = clock,
        )

        try {
            controller.start()
            advanceTimeBy(100)
            // Run started
            assertEquals(1, delegate.syncNowCalls)

            controller.onVisibilityChanged(false)
            advanceTimeBy(50)

            // Engine stop was called
            assertEquals(1, stopCount)
            // State is not Syncing
            val state = controller.state.value
            assertTrue(state !is PluginState.Syncing)
        } finally {
            controller.stop()
            scope.cancel()
        }
    }

    @Test
    fun notReadyIsNotConnectedWithoutNetwork() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(testDispatcher)
        val clock = TestClock()

        val source = FakeEngineSource(null)
        val secrets = DeviceSecretStore(FakeLocalStorage())
        val settings = testSettingsStore()

        val controller = PluginSyncController(
            scope = scope,
            assembler = source,
            probe = fakeProbe(DaemonPresence.Absent),
            secrets = secrets,
            settings = settings,
            vaultEvents = MutableSharedFlow(),
            clock = clock,
        )

        try {
            controller.start()
            advanceTimeBy(100)
            assertEquals(PluginState.NotConnected, controller.state.value)
        } finally {
            controller.stop()
            scope.cancel()
        }
    }

    @Test
    fun daemonServingVaultYields() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(testDispatcher)
        val clock = TestClock()

        val engine = FakeSyncEngine()
        val remoteEntry = RemoteEntry(
            path = RemotePath("test.md"),
            size = 10,
            modifiedAt = Instant.fromEpochMilliseconds(1000),
            revision = "r1",
            isDirectory = false,
        )
        val change = dev.mimir.core.RemoteChange(RemotePath("test.md"), isDeleted = false, entry = remoteEntry)
        val feed = FakeChangeFeed(changes = listOf(change))
        val source = FakeEngineSource(engine to feed)
        val secrets = DeviceSecretStore(FakeLocalStorage())
        val settings = testSettingsStore()
        settings.update { it.copy(rootFolder = "V") }

        val controller = PluginSyncController(
            scope = scope,
            assembler = source,
            probe = fakeProbe(DaemonPresence.ServesThisVault),
            secrets = secrets,
            settings = settings,
            vaultEvents = MutableSharedFlow(),
            clock = clock,
        )

        try {
            controller.start()
            advanceTimeBy(100)
            assertEquals(0, engine.syncNowCalls)
            assertEquals(PluginState.YieldsToDaemon, controller.state.value)
        } finally {
            controller.stop()
            scope.cancel()
        }
    }

    @Test
    fun unauthorizedDaemonPausesUnlessForced() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(testDispatcher)
        val clock = TestClock()

        val engine = FakeSyncEngine()
        val remoteEntry = RemoteEntry(
            path = RemotePath("test.md"),
            size = 10,
            modifiedAt = Instant.fromEpochMilliseconds(1000),
            revision = "r1",
            isDirectory = false,
        )
        val change = dev.mimir.core.RemoteChange(RemotePath("test.md"), isDeleted = false, entry = remoteEntry)
        val feed = FakeChangeFeed(changes = listOf(change))
        val source = FakeEngineSource(engine to feed)
        val secrets = DeviceSecretStore(FakeLocalStorage())
        val settings = testSettingsStore()
        settings.update { it.copy(rootFolder = "V", forceSyncWithDaemon = false) }

        val controller = PluginSyncController(
            scope = scope,
            assembler = source,
            probe = fakeProbe(DaemonPresence.Unauthorized),
            secrets = secrets,
            settings = settings,
            vaultEvents = MutableSharedFlow(),
            clock = clock,
        )

        try {
            controller.start()
            advanceTimeBy(100)
            assertEquals(0, engine.syncNowCalls)
            assertEquals(PluginState.PausedForDaemon, controller.state.value)

            // Now set forceSyncWithDaemon = true
            settings.update { it.copy(forceSyncWithDaemon = true) }
            controller.trigger(Trigger.Manual)
            advanceTimeBy(100)
            assertEquals(1, engine.syncNowCalls)
            assertIs<PluginState.UpToDate>(controller.state.value)
        } finally {
            controller.stop()
            scope.cancel()
        }
    }

    @Test
    fun offlineMapsToOffline() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(testDispatcher)
        val clock = TestClock()

        val engine = FakeSyncEngine(onSync = { throw RemoteUnavailableException("Network is down") })
        val source = FakeEngineSource(engine to FakeChangeFeed())
        val secrets = DeviceSecretStore(FakeLocalStorage())
        val settings = testSettingsStore()
        settings.update { it.copy(rootFolder = "V") }

        val controller = PluginSyncController(
            scope = scope,
            assembler = source,
            probe = fakeProbe(DaemonPresence.Absent),
            secrets = secrets,
            settings = settings,
            vaultEvents = MutableSharedFlow(),
            clock = clock,
        )

        try {
            controller.start()
            advanceTimeBy(100)
            assertEquals(PluginState.Offline, controller.state.value)
        } finally {
            controller.stop()
            scope.cancel()
        }
    }

    @Test
    fun authErrorMapsToNeedsSignIn() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(testDispatcher)
        val clock = TestClock()

        val engine = FakeSyncEngine(onSync = { throw DriveAuthException(HttpStatusCode.Unauthorized, "401 Unauthorized") })
        val source = FakeEngineSource(engine to FakeChangeFeed())
        val secrets = DeviceSecretStore(FakeLocalStorage())
        val settings = testSettingsStore()
        settings.update { it.copy(rootFolder = "V") }

        val controller = PluginSyncController(
            scope = scope,
            assembler = source,
            probe = fakeProbe(DaemonPresence.Absent),
            secrets = secrets,
            settings = settings,
            vaultEvents = MutableSharedFlow(),
            clock = clock,
        )

        try {
            controller.start()
            advanceTimeBy(100)
            assertIs<PluginState.NeedsSignIn>(controller.state.value)
        } finally {
            controller.stop()
            scope.cancel()
        }
    }

    @Test
    fun abortedMapsToError() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(testDispatcher)
        val clock = TestClock()

        val engine = FakeSyncEngine(onSync = { throw SyncAbortedException("Drive is empty") })
        val source = FakeEngineSource(engine to FakeChangeFeed())
        val secrets = DeviceSecretStore(FakeLocalStorage())
        val settings = testSettingsStore()
        settings.update { it.copy(rootFolder = "V") }

        val controller = PluginSyncController(
            scope = scope,
            assembler = source,
            probe = fakeProbe(DaemonPresence.Absent),
            secrets = secrets,
            settings = settings,
            vaultEvents = MutableSharedFlow(),
            clock = clock,
        )

        try {
            controller.start()
            advanceTimeBy(100)
            val state = assertIs<PluginState.Error>(controller.state.value)
            assertTrue("Drive is empty" in state.message || "nothing was deleted" in state.message)
        } finally {
            controller.stop()
            scope.cancel()
        }
    }
}
