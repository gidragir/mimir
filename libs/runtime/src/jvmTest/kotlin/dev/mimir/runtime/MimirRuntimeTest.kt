package dev.mimir.runtime

import dev.mimir.client.DaemonLoginMethod
import dev.mimir.client.DaemonLoginPoll
import dev.mimir.client.DaemonLoginStart
import dev.mimir.core.RemotePath
import dev.mimir.core.SyncStatus
import dev.mimir.core.label
import dev.mimir.core.InMemoryRemoteStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MimirRuntimeTest {
    private val configDir = Files.createTempDirectory("mimir-runtime-cfg")
    private val vaultDir = Files.createTempDirectory("mimir-runtime-vault")
    private val remote = InMemoryRemoteStorage()
    private var runtime: MimirRuntime? = null

    private fun run(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking { block() }

    @AfterTest
    fun cleanup() {
        runtime?.close()
        configDir.toFile().deleteRecursively()
        vaultDir.toFile().deleteRecursively()
    }

    private fun runtime(
        vault: Boolean = true,
        password: String? = null,
        cloud: CloudStorageFactory = CloudStorageFactory { _, _, _ -> remote },
        httpClient: io.ktor.client.HttpClient = jsonHttpClient(),
        desktopClient: Boolean = false,
    ) = testRuntime(
        configDir,
        vaultPath = vaultDir.toString().takeIf { vault },
        password = password,
        cloud = cloud,
        httpClient = httpClient,
        desktopClient = desktopClient,
    ).also { runtime = it }

    private suspend fun signIn() = dev.mimir.runtime.signIn(configDir)

    private suspend fun eventually(block: () -> Boolean) = withTimeout(15_000) {
        while (!block()) delay(50)
    }

    @Test
    fun `without vault or tokens the status is not connected`() = run {
        val runtime = runtime(vault = false)
        runtime.initialize()

        assertEquals(SyncStatus.NotConfigured, runtime.status.value)
        assertEquals("Mimir: not connected", runtime.status.value.label())
        assertTrue(runtime.isLocked)
        assertFailsWith<SyncNotConfiguredException> { runtime.triggerSync() }
    }

    @Test
    fun `signed in but locked stays not connected until unlock`() = run {
        signIn()
        val runtime = runtime()
        runtime.initialize()
        assertEquals(SyncStatus.NotConfigured, runtime.status.value)
        assertTrue(runtime.isLocked)

        runtime.unlock("secret")

        assertFalse(runtime.isLocked)
        assertTrue(runtime.isReady)
    }

    @Test
    fun `ready runtime encrypts and uploads local notes on manual sync`() = run {
        signIn()
        vaultDir.resolve("Notes").createDirectories()
        vaultDir.resolve("Notes/Idea.md").writeText("hello")
        val runtime = runtime(password = "secret")
        runtime.initialize()
        assertTrue(runtime.isReady)

        eventually { runtime.lastSyncTime != null }

        val rootEntries = remote.list(RemotePath("")).filter { it.path.value != CANARY_FILENAME }
        assertEquals(1, rootEntries.size)
        assertTrue("Notes" !in rootEntries.single().path.value, "names must be encrypted")
        assertNotNull(runtime.lastSyncTime)
    }

    @Test
    fun `starting offline with an unverified password stays locked instead of crashing`() = run {
        signIn()
        val offline = object : dev.mimir.core.ChangeFeedStorage by remote {
            override suspend fun stat(path: RemotePath) = throw java.io.IOException("offline")
        }
        val runtime = runtime(password = "secret", cloud = { _, _, _ -> offline })
        runtime.initialize()
        assertFalse(runtime.isReady)
    }

    @Test
    fun `unlock reports that the network is needed when the password was never verified`() = run {
        signIn()
        val offline = object : dev.mimir.core.ChangeFeedStorage by remote {
            override suspend fun stat(path: RemotePath) = throw java.io.IOException("offline")
        }
        val runtime = runtime(cloud = { _, _, _ -> offline })
        runtime.initialize()
        assertFailsWith<SyncNotConfiguredException> { runtime.unlock("secret") }
    }

    @Test
    fun `a wrong password on re-unlock keeps the running sync`() = run {
        signIn()
        val runtime = runtime(password = "secret")
        runtime.initialize()
        assertTrue(runtime.isReady)

        assertFailsWith<InvalidPasswordException> { runtime.unlock("typo") }

        assertTrue(runtime.isReady)
        assertFalse(runtime.isLocked)
    }

    @Test
    fun `runtime exposes engine progress`() = run {
        signIn()
        vaultDir.resolve("a.md").writeText("hello")
        val runtime = runtime(password = "secret")
        runtime.initialize()
        eventually { runtime.progress.value?.phase == dev.mimir.core.SyncPhase.Finished }
        assertEquals(dev.mimir.core.SyncPhase.Finished, runtime.progress.value?.phase)
    }

    @Test
    fun `last sync summary survives engine rebuild`() = run {
        signIn()
        vaultDir.resolve("a.md").writeText("hello")
        val runtime = runtime(password = "secret")
        runtime.initialize()
        eventually { runtime.progress.value?.phase == dev.mimir.core.SyncPhase.Finished }

        val seen = java.util.Collections.synchronizedList(mutableListOf<dev.mimir.core.SyncProgress?>())
        val collector = launch(kotlinx.coroutines.Dispatchers.Unconfined) { runtime.progress.collect { seen += it } }
        runtime.unlock("secret")
        delay(500)
        collector.cancel()

        assertTrue(seen.none { it == null }, "progress was reset to null after rebuild: $seen")
        assertNotNull(runtime.progress.value)
    }

    @Test
    fun `authorized poll writes account json with method and rebuilds once`() = run {
        var builds = 0
        val runtime = runtime(httpClient = mockGoogle(), cloud = { _, _, _ -> builds++; remote })
        runtime.unlock("secret") // без токенов сборка до облака не доходит
        assertEquals(0, builds)

        val start = runtime.startLogin(DaemonLoginMethod.DeviceCode)
        assertEquals("UC", assertIs<DaemonLoginStart.ShowDeviceCode>(start).code.userCode)
        assertEquals(DaemonLoginPoll.Authorized("user@gmail.com"), runtime.pollLogin())
        assertEquals(DaemonLoginPoll.Authorized("user@gmail.com"), runtime.pollLogin())

        assertEquals(1, builds)
        assertEquals("user@gmail.com", runtime.accountEmail)
        val account = kotlinx.serialization.json.Json.parseToJsonElement(Files.readString(configDir.resolve("account.json"))).jsonObject
        assertEquals("user@gmail.com", account.getValue("email").jsonPrimitive.content)
        assertEquals("device_code", account.getValue("method").jsonPrimitive.content)
        assertTrue(runtime.isReady)
    }

    @Test
    fun `login methods follow configured google clients`() = run {
        val both = testRuntime(configDir, desktopClient = true)
        try {
            assertEquals(setOf(DaemonLoginMethod.Browser, DaemonLoginMethod.DeviceCode), both.loginMethods())
        } finally {
            both.close()
        }
        assertEquals(setOf(DaemonLoginMethod.DeviceCode), runtime().loginMethods())
    }

    @Test
    fun `unregistered login method is not configured`() = run {
        val runtime = runtime()
        assertFailsWith<SyncNotConfiguredException> { runtime.startLogin(DaemonLoginMethod.Browser) }
        assertFailsWith<SyncNotConfiguredException> { runtime.startLogin(DaemonLoginMethod.SystemAccount) }
    }

    @Test
    fun `poll before start fails`() = run {
        assertIs<DaemonLoginPoll.Failed>(runtime().pollLogin())
    }

    @Test
    fun `switching login method cancels the previous attempt`() = run {
        val runtime = runtime(httpClient = mockGoogle(), desktopClient = true)
        val url = assertIs<DaemonLoginStart.OpenUrl>(runtime.startLogin(DaemonLoginMethod.Browser)).url
        val port = Regex("redirect_uri=http://127\\.0\\.0\\.1:(\\d+)/callback").find(url)!!.groupValues[1].toInt()

        runtime.startLogin(DaemonLoginMethod.DeviceCode)

        // Приёмник прежней попытки закрыт: поздний ответ из открытой вкладки браузера не перезапишет вход по коду.
        withTimeout(15_000) {
            while (runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 500) } }.isSuccess) delay(50)
        }
        assertEquals(DaemonLoginPoll.Authorized("user@gmail.com"), runtime.pollLogin())
        assertEquals("device_code", Json.parseToJsonElement(Files.readString(configDir.resolve("account.json"))).jsonObject.getValue("method").jsonPrimitive.content)
    }

    private fun tokenSourceSeenByCloud(account: String?, clientKind: dev.mimir.drive.auth.GoogleClientKind): dev.mimir.drive.auth.AccessTokenSource? = runBlocking {
        val browser = FakeLoginProvider(DaemonLoginMethod.Browser)
        val device = FakeLoginProvider(DaemonLoginMethod.DeviceCode)
        account?.let { Files.writeString(configDir.resolve("account.json"), it) }
        FileTokenStorage(configDir.resolve("tokens.json")).save(dev.mimir.drive.auth.OAuthTokens("a", "r", 4_000_000_000L, clientKind = clientKind))
        var seen: dev.mimir.drive.auth.AccessTokenSource? = null
        val rt = testRuntime(
            configDir,
            vaultPath = vaultDir.toString(),
            password = "secret",
            cloud = { _, tokens, _ -> seen = tokens; remote },
            providers = listOf(browser, device),
        ).also { runtime = it }
        rt.setInteractive(false)
        rt.initialize()
        assertTrue(rt.isReady)
        lastProviders = browser to device
        seen
    }

    private var lastProviders: Pair<FakeLoginProvider, FakeLoginProvider>? = null

    @Test
    fun `drive uses the token source of the signed-in method`() {
        val seen = tokenSourceSeenByCloud("""{"email":"u@x","method":"browser"}""", dev.mimir.drive.auth.GoogleClientKind.Device)
        kotlin.test.assertSame(lastProviders!!.first.tokenSource, seen)
    }

    @Test
    fun `legacy account without method follows the token client kind`() {
        val desktop = tokenSourceSeenByCloud("""{"email":"u@x"}""", dev.mimir.drive.auth.GoogleClientKind.Desktop)
        kotlin.test.assertSame(lastProviders!!.first.tokenSource, desktop)
        runtime?.close()
        runtime = null

        val device = tokenSourceSeenByCloud(null, dev.mimir.drive.auth.GoogleClientKind.Device)
        kotlin.test.assertSame(lastProviders!!.second.tokenSource, device)
    }
}
