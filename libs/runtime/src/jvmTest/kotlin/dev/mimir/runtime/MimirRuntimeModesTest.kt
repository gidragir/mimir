package dev.mimir.runtime

import dev.mimir.client.DaemonLoginMethod
import dev.mimir.client.wireName
import dev.mimir.core.EncryptedRemoteStorage
import dev.mimir.core.InMemoryRemoteStorage
import dev.mimir.core.RcloneCipher
import dev.mimir.core.RemotePath
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MimirRuntimeModesTest {
    private val dataDir = Files.createTempDirectory("mimir-modes-data")
    private val vaultDir = Files.createTempDirectory("mimir-modes-vault")
    private val remote = InMemoryRemoteStorage()
    private var runtime: MimirRuntime? = null

    @AfterTest
    fun cleanup() {
        runtime?.close()
        dataDir.toFile().deleteRecursively()
        vaultDir.toFile().deleteRecursively()
    }

    private fun runtime(watchers: VaultWatcherFactory = nioWatchers, providers: List<dev.mimir.runtime.login.LoginProvider>? = null) =
        testRuntime(
            dataDir,
            vaultPath = vaultDir.toString(),
            password = "secret",
            cloud = { _, _, _ -> remote },
            watcherFactory = watchers,
            providers = providers,
        ).also { runtime = it }

    private suspend fun eventually(block: () -> Boolean) = withTimeout(15_000) {
        while (!block()) delay(20)
    }

    @Test
    fun `setInteractive false stops watcher and scheduler`() = runBlocking {
        signIn(dataDir)
        val watchers = FakeWatcherFactory()
        val rt = runtime(watchers)
        rt.initialize()
        assertTrue(rt.isReady)
        // наблюдатель движка (через планировщик) и наблюдатель конфликтов
        eventually { watchers.activeCount() == 2 }

        rt.setInteractive(false)

        eventually { watchers.activeCount() == 0 }
        assertTrue(watchers.created.all { it.second.stops.get() > 0 }, "every watcher must be stopped")
        assertTrue(rt.isReady, "the engine stays assembled for syncOnce")

        rt.setInteractive(true)
        eventually { watchers.activeCount() == 2 }
    }

    @Test
    fun `setInteractive false does not cancel in-flight sync`(): Unit = runBlocking {
        signIn(dataDir)
        vaultDir.resolve("a.md").writeText("hello")
        val rt = runtime()
        rt.initialize()
        assertTrue(rt.isReady)

        // Запускаем ручной syncOnce в фоне (контекст фонового воркера)
        val syncJob = async { rt.syncOnce() }
        // Переключаем в неинтерактивный режим во время синхронизации
        rt.setInteractive(false)

        assertTrue(syncJob.await(), "in-flight syncOnce must succeed")
        assertNotNull(rt.lastSyncTime)
    }

    @Test
    fun `setInteractive false cancels interactive runner and reports background sync needed`(): Unit = runBlocking {
        signIn(dataDir)
        vaultDir.resolve("a.md").writeText("hello")
        val listGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gatedRemote = object : dev.mimir.core.ChangeFeedStorage by remote {
            override suspend fun listAll(
                root: RemotePath,
                onProgress: (folders: Int, files: Int) -> Unit,
            ): List<dev.mimir.core.RemoteEntry> {
                listGate.await()
                return remote.listAll(root, onProgress)
            }
        }
        val rt = testRuntime(
            dataDir,
            vaultPath = vaultDir.toString(),
            password = "secret",
            cloud = { _, _, _ -> gatedRemote },
        ).also { runtime = it }
        rt.initialize()
        assertTrue(rt.isReady)
        eventually { rt.status.value == dev.mimir.core.SyncStatus.Syncing }

        val needsBackgroundSync = rt.setInteractive(false)

        assertTrue(needsBackgroundSync, "interrupted interactive sync must request background continuation")
        eventually { rt.status.value == dev.mimir.core.SyncStatus.Idle }
    }

    @Test
    fun `runtime created with initialInteractive false does not start background timers on initialize`(): Unit = runBlocking {
        signIn(dataDir)
        vaultDir.resolve("a.md").writeText("hello")
        val watchers = FakeWatcherFactory()
        val settings = JsonRuntimeSettingsStore(dataDir).apply { saveVaultPath(vaultDir.toString()) }
        val rt = MimirRuntime(
            settings = settings,
            logins = dev.mimir.runtime.login.LoginRegistry(listOf(FakeLoginProvider(DaemonLoginMethod.DeviceCode))),
            watcherFactory = watchers,
            passwordProvider = { "secret" },
            ioDispatcher = kotlinx.coroutines.Dispatchers.IO,
            httpClient = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO),
            cloudFactory = { _, _, _ -> remote },
            initialInteractive = false,
        ).also { runtime = it }

        rt.initialize()
        delay(200)

        assertTrue(rt.isReady)
        assertEquals(0, watchers.activeCount(), "background worker initialization must not start watchers")
        assertNull(rt.lastSyncTime, "background worker initialization must not start scheduler timer")
        assertTrue(rt.syncOnce())
        assertNotNull(rt.lastSyncTime)
    }

    @Test
    fun `syncOnce runs while not interactive`() = runBlocking {
        signIn(dataDir)
        vaultDir.resolve("a.md").writeText("hello")
        val rt = runtime()
        rt.setInteractive(false)
        rt.initialize()
        delay(300)
        assertNull(rt.lastSyncTime, "nothing runs in the background mode on its own")

        assertTrue(rt.syncOnce())

        assertTrue(rt.lastSyncTime != null)
        assertTrue(remote.list(RemotePath("")).any { it.path.value != CANARY_FILENAME })
    }

    @Test
    fun `syncOnce returns false while initial merge is held`() = runBlocking {
        val unconfigured = testRuntime(dataDir).also { runtime = it }
        unconfigured.initialize()
        assertFalse(unconfigured.syncOnce())
        unconfigured.close()

        signIn(dataDir)
        val rt = testRuntime(dataDir, password = "secret", cloud = { _, _, _ -> remote }).also { runtime = it }
        rt.initialize()
        rt.configureVault(vaultDir.toString()) // первая настройка: ждём выбора стратегии слияния
        assertTrue(rt.isReady)

        assertFalse(rt.syncOnce())
        assertNull(rt.lastSyncTime)
    }

    @Test
    fun `recentlyWrittenBySync lists downloaded paths from last progress`() = runBlocking {
        signIn(dataDir)
        val rt = runtime()
        rt.setInteractive(false)
        rt.initialize()
        EncryptedRemoteStorage(remote, RcloneCipher.create("secret"))
            .write(RemotePath("Notes/r.md"), Buffer().also { it.write("remote".encodeToByteArray()) })

        assertTrue(rt.syncOnce())

        assertEquals("remote", Files.readString(vaultDir.resolve("Notes/r.md")))
        assertEquals(setOf("Notes/r.md"), rt.recentlyWrittenBySync())
    }

    @Test
    fun `signOut calls provider signOut, deletes account json and keeps index and vault`() = runBlocking {
        val provider = FakeLoginProvider(DaemonLoginMethod.DeviceCode)
        dataDir.resolve("account.json").writeText("""{"email":"user@example.com","method":"${DaemonLoginMethod.DeviceCode.wireName}"}""")
        vaultDir.resolve("a.md").writeText("hello")
        dataDir.resolve("index.json").writeText("""{"version":1,"records":[]}""")
        val rt = runtime(providers = listOf(provider))
        rt.initialize()
        assertEquals("user@example.com", rt.accountEmail)
        assertTrue(rt.isReady)

        rt.signOut()

        assertEquals(1, provider.signOuts.get())
        assertFalse(dataDir.resolve("account.json").exists())
        assertTrue(dataDir.resolve("index.json").exists())
        assertTrue(vaultDir.resolve("a.md").exists())
        assertNull(rt.accountEmail)
        assertFalse(rt.isReady)
    }
}
