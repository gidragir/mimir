package dev.mimir.daemon

import dev.mimir.runtime.SyncNotConfiguredException

import dev.mimir.runtime.FileTokenStorage
import dev.mimir.runtime.InvalidPasswordException
import dev.mimir.runtime.CANARY_FILENAME

import dev.mimir.client.DaemonLoginMethod
import dev.mimir.client.DaemonLoginPoll
import dev.mimir.client.DaemonLoginStart
import dev.mimir.core.RemotePath
import dev.mimir.core.SyncStatus
import dev.mimir.core.label
import dev.mimir.core.InMemoryRemoteStorage
import dev.mimir.drive.auth.OAuthTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import io.ktor.client.engine.mock.respond
import io.ktor.serialization.kotlinx.json.json
import java.nio.file.Files
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

class DaemonRuntimeTest {
    private val configDir = Files.createTempDirectory("mimir-runtime-cfg")
    private val vaultDir = Files.createTempDirectory("mimir-runtime-vault")
    private val remote = InMemoryRemoteStorage()
    private var runtime: DaemonRuntime? = null

    private fun run(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking { block() }

    @AfterTest
    fun cleanup() {
        runtime?.close()
        configDir.toFile().deleteRecursively()
        vaultDir.toFile().deleteRecursively()
    }

    private fun config(vault: Boolean = true, password: String? = null) = DaemonConfig(
        port = 0,
        vaultPath = vaultDir.toString().takeIf { vault },
        password = password,
        googleDeviceClientId = "client-id",
        syncIntervalSeconds = 3600,
        configDir = configDir.toString(),
    )

    private fun runtime(config: DaemonConfig) =
        DaemonRuntime(config, remoteStorageFactory = { _, _, _ -> remote }).also { runtime = it }

    private suspend fun signIn() {
        FileTokenStorage(configDir.resolve("tokens.json")).save(OAuthTokens("a", "r", 4_000_000_000L))
    }

    private suspend fun eventually(block: () -> Boolean) = withTimeout(15_000) {
        while (!block()) delay(50)
    }

    @Test
    fun `without vault or tokens the status is not connected`() = run {
        val runtime = runtime(config(vault = false))
        runtime.initialize()

        assertEquals(SyncStatus.NotConfigured, runtime.status.value)
        assertEquals("Mimir: not connected", runtime.status.value.label())
        assertTrue(runtime.isLocked)
        assertFailsWith<SyncNotConfiguredException> { runtime.triggerSync() }
    }

    @Test
    fun `signed in but locked stays not connected until unlock`() = run {
        signIn()
        val runtime = runtime(config())
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
        val runtime = runtime(config(password = "secret"))
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
        val runtime = DaemonRuntime(config(password = "secret"), remoteStorageFactory = { _, _, _ -> offline }).also { this@DaemonRuntimeTest.runtime = it }
        runtime.initialize()
        assertFalse(runtime.isReady)
    }

    @Test
    fun `unlock reports that the network is needed when the password was never verified`() = run {
        signIn()
        val offline = object : dev.mimir.core.ChangeFeedStorage by remote {
            override suspend fun stat(path: RemotePath) = throw java.io.IOException("offline")
        }
        val runtime = DaemonRuntime(config(), remoteStorageFactory = { _, _, _ -> offline }).also { this@DaemonRuntimeTest.runtime = it }
        runtime.initialize()
        assertFailsWith<SyncNotConfiguredException> { runtime.unlock("secret") }
    }

    @Test
    fun `a wrong password on re-unlock keeps the running sync`() = run {
        signIn()
        val runtime = runtime(config(password = "secret"))
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
        val runtime = runtime(config(password = "secret"))
        runtime.initialize()
        eventually { runtime.progress.value?.phase == dev.mimir.core.SyncPhase.Finished }
        assertEquals(dev.mimir.core.SyncPhase.Finished, runtime.progress.value?.phase)
    }

    @Test
    fun `last sync summary survives engine rebuild`() = run {
        signIn()
        vaultDir.resolve("a.md").writeText("hello")
        val runtime = runtime(config(password = "secret"))
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

    private fun mockGoogle() = io.ktor.client.HttpClient(
        io.ktor.client.engine.mock.MockEngine { request ->
            val body = when {
                request.url.encodedPath.endsWith("/device/code") ->
                    """{"device_code":"dc","user_code":"UC","verification_url":"https://www.google.com/device","expires_in":1800,"interval":5}"""
                request.url.encodedPath.endsWith("/token") ->
                    """{"access_token":"at","refresh_token":"rt","expires_in":3600,"token_type":"Bearer"}"""
                request.url.encodedPath.endsWith("/userinfo") -> """{"email":"user@gmail.com","name":"User"}"""
                else -> "{}"
            }
            respond(
                body,
                io.ktor.http.HttpStatusCode.OK,
                io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"),
            )
        },
    ) {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json(kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
        }
    }

    @Test
    fun `authorized poll writes account json with method and rebuilds once`() = run {
        var builds = 0
        val runtime = DaemonRuntime(
            config(),
            httpClient = mockGoogle(),
            remoteStorageFactory = { _, _, _ -> builds++; remote },
        ).also { runtime = it }
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
        val both = DaemonRuntime(config().copy(googleDesktopClientId = "desktop-id"))
        try {
            assertEquals(setOf(DaemonLoginMethod.Browser, DaemonLoginMethod.DeviceCode), both.loginMethods())
        } finally {
            both.close()
        }
        assertEquals(setOf(DaemonLoginMethod.DeviceCode), runtime(config()).loginMethods())
    }

    @Test
    fun `unregistered login method is not configured`() = run {
        val runtime = runtime(config())
        assertFailsWith<SyncNotConfiguredException> { runtime.startLogin(DaemonLoginMethod.Browser) }
        assertFailsWith<SyncNotConfiguredException> { runtime.startLogin(DaemonLoginMethod.SystemAccount) }
    }

    @Test
    fun `poll before start fails`() = run {
        assertIs<DaemonLoginPoll.Failed>(runtime(config()).pollLogin())
    }
}
