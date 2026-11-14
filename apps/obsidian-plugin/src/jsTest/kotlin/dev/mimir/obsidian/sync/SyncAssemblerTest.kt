package dev.mimir.obsidian.sync

import dev.mimir.core.CanaryVerifier
import dev.mimir.core.CountingRemoteStorage
import dev.mimir.core.InMemoryRemoteStorage
import dev.mimir.core.RcloneCipher
import dev.mimir.core.RemotePath
import dev.mimir.core.sync.InMemorySyncStateStore
import dev.mimir.core.testing.InMemoryLocalVault
import dev.mimir.core.testing.TestCiphers
import dev.mimir.drive.GoogleDriveStorage
import dev.mimir.drive.auth.GoogleClientKind
import dev.mimir.drive.auth.OAuthTokens
import dev.mimir.drive.path.DrivePathResolver
import dev.mimir.obsidian.fakes.FakeLocalStorage
import dev.mimir.obsidian.state.DeviceSecretStore
import dev.mimir.obsidian.state.PluginSettings
import dev.mimir.obsidian.state.PluginSettingsStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SyncAssemblerTest {

    private fun testHttpClient(): HttpClient = HttpClient(MockEngine { respond("") })

    private fun testTokens(): OAuthTokens = OAuthTokens(
        accessToken = "at",
        refreshToken = "rt",
        expiresAtEpochSeconds = 2000000000L,
        clientKind = GoogleClientKind.Device,
    )

    private suspend fun testSettingsStore(initial: PluginSettings = PluginSettings()): PluginSettingsStore {
        var inMemorySettings: dynamic = null
        val store = PluginSettingsStore(load = { inMemorySettings }, save = { inMemorySettings = it })
        if (initial != PluginSettings()) {
            store.update { initial }
        }
        return store
    }

    @Test
    fun emptyFolderCreatesCanaryAndSavesKey() = runTest {
        val http = testHttpClient()
        val storage = FakeLocalStorage()
        val secrets = DeviceSecretStore(storage)
        secrets.save(testTokens())

        val settings = testSettingsStore(PluginSettings(rootFolder = "MyVault"))

        val remote = InMemoryRemoteStorage()
        val countingRemote = CountingRemoteStorage(remote)
        val vault = InMemoryLocalVault()
        val stateStore = InMemorySyncStateStore()

        val assembler = SyncAssembler(
            http = http,
            secrets = secrets,
            settings = settings,
            vault = vault,
            stateStore = stateStore,
            remoteFactory = { _, _ -> countingRemote },
            cipherFactory = { password, salt -> TestCiphers.forPassword(password, salt) },
        )

        val result = assembler.setupKey(password = "test-pass", salt = null)
        val ok = assertIs<KeySetup.Ok>(result)
        assertTrue(ok.firstDevice)
        assertEquals(1, countingRemote.statCalls)

        // Key and canary saved in secrets
        val key = secrets.loadKey()
        assertNotNull(key)
        assertEquals(80, key.size)
        assertNotNull(secrets.loadCanaryHash())

        // Remote contains canary
        val canaryPath = RemotePath(CanaryVerifier.CANARY_FILENAME)
        assertNotNull(remote.stat(canaryPath))
        val cipher = RcloneCipher.fromKeyBytes(key)
        val canaryContent = cipher.decrypt(remote.read(canaryPath)).readString()
        assertEquals(CanaryVerifier.CANARY_BODY, canaryContent)
    }

    @Test
    fun correctPasswordOnSecondDevice() = runTest {
        val http = testHttpClient()
        val remote = InMemoryRemoteStorage()

        // First device creates canary
        val cipher = TestCiphers.forPassword("correct-password")
        val canaryData = Buffer().apply { write(CanaryVerifier.CANARY_BODY.encodeToByteArray()) }
        remote.write(RemotePath(CanaryVerifier.CANARY_FILENAME), cipher.encrypt(canaryData))

        // Second device with fresh storage
        val storage = FakeLocalStorage()
        val secrets = DeviceSecretStore(storage)
        secrets.save(testTokens())

        val settings = testSettingsStore(PluginSettings(rootFolder = "MyVault"))

        val assembler = SyncAssembler(
            http = http,
            secrets = secrets,
            settings = settings,
            vault = InMemoryLocalVault(),
            stateStore = InMemorySyncStateStore(),
            remoteFactory = { _, _ -> remote },
            cipherFactory = { password, salt -> TestCiphers.forPassword(password, salt) },
        )

        val result = assembler.setupKey(password = "correct-password", salt = null)
        val ok = assertIs<KeySetup.Ok>(result)
        assertFalse(ok.firstDevice)

        val key = secrets.loadKey()
        assertNotNull(key)
        assertEquals(80, key.size)
    }

    @Test
    fun wrongPasswordWritesNothing() = runTest {
        val http = testHttpClient()
        val remote = InMemoryRemoteStorage()

        // Existing vault with password "right-pass"
        val rightCipher = TestCiphers.forPassword("right-pass")
        val canaryData = Buffer().apply { write(CanaryVerifier.CANARY_BODY.encodeToByteArray()) }
        remote.write(RemotePath(CanaryVerifier.CANARY_FILENAME), rightCipher.encrypt(canaryData))

        val initialRemoteFiles = remote.list(RemotePath("")).associate { it.path.value to remote.read(it.path).readByteArray() }

        val storage = FakeLocalStorage()
        val secrets = DeviceSecretStore(storage)
        secrets.save(testTokens())

        val settings = testSettingsStore(PluginSettings(rootFolder = "MyVault"))

        val vault = InMemoryLocalVault()
        val assembler = SyncAssembler(
            http = http,
            secrets = secrets,
            settings = settings,
            vault = vault,
            stateStore = InMemorySyncStateStore(),
            remoteFactory = { _, _ -> remote },
            cipherFactory = { password, salt -> TestCiphers.forPassword(password, salt) },
        )

        val result = assembler.setupKey(password = "wrong-pass", salt = null)
        assertIs<KeySetup.WrongPassword>(result)

        assertNull(secrets.loadKey())
        assertNull(secrets.loadCanaryHash())
        assertEquals(0, vault.list().size)

        // Verify remote files are completely unchanged
        val currentRemoteFiles = remote.list(RemotePath("")).associate { it.path.value to remote.read(it.path).readByteArray() }
        assertEquals(initialRemoteFiles.size, currentRemoteFiles.size)
        for ((path, bytes) in initialRemoteFiles) {
            assertTrue(currentRemoteFiles[path]!!.contentEquals(bytes))
        }
    }

    @Test
    fun notReadyWithoutTokensOrFolder() = runTest {
        val http = testHttpClient()
        val storage = FakeLocalStorage()
        val secrets = DeviceSecretStore(storage)
        val settings = testSettingsStore(PluginSettings(rootFolder = ""))

        val assembler = SyncAssembler(
            http = http,
            secrets = secrets,
            settings = settings,
            vault = InMemoryLocalVault(),
            stateStore = InMemorySyncStateStore(),
            remoteFactory = { _, _ -> InMemoryRemoteStorage() },
        )

        assertFalse(assembler.isReady())
        assertNull(assembler.buildEngine())

        // Add tokens but still empty rootFolder
        secrets.save(testTokens())
        secrets.saveKey(ByteArray(80))
        assertFalse(assembler.isReady())
        assertNull(assembler.buildEngine())
    }

    @Test
    fun engineSyncsRoundTrip() = runTest {
        val http = testHttpClient()
        val storage = FakeLocalStorage()
        val secrets = DeviceSecretStore(storage)
        secrets.save(testTokens())

        val cipher = TestCiphers.forPassword("sync-password")
        secrets.saveKey(cipher.exportKeyBytes())
        secrets.saveCanaryHash(CanaryVerifier.computeCanaryHash(cipher))

        val settings = testSettingsStore(PluginSettings(rootFolder = "VaultFolder", parallelism = 2))

        val remote = InMemoryRemoteStorage()
        val vault = InMemoryLocalVault()
        vault.write("a/b/c.md", Buffer().apply { write("note-data".encodeToByteArray()) })

        val assembler = SyncAssembler(
            http = http,
            secrets = secrets,
            settings = settings,
            vault = vault,
            stateStore = InMemorySyncStateStore(),
            remoteFactory = { _, _ -> remote },
            cipherFactory = { password, salt -> TestCiphers.forPassword(password, salt) },
        )

        assertTrue(assembler.isReady())
        val pair = assembler.buildEngine()
        assertNotNull(pair)
        val (engine, feed) = pair

        engine.syncNow()
        val progress = engine.progress.value
        assertNotNull(progress)
        assertEquals(0, progress.problems.size)

        // Remote now contains encrypted file for a/b/c.md (three directory/file segments)
        val remoteList = remote.list(RemotePath(""))
        assertTrue(remoteList.isNotEmpty())
        val firstSegment = remoteList.first().path.segments.first()
        // Ensure name is encrypted
        assertEquals("a", cipher.decryptName(firstSegment))
    }

    @Test
    fun buildEngineReusesResolverUntilRootFolderChangesOrClearKey() = runTest {
        val http = testHttpClient()
        val storage = FakeLocalStorage()
        val secrets = DeviceSecretStore(storage)
        secrets.save(testTokens())

        val cipher = TestCiphers.forPassword("reuse-password")
        secrets.saveKey(cipher.exportKeyBytes())

        val settings = testSettingsStore(PluginSettings(rootFolder = "VaultA"))
        val resolvers = mutableListOf<DrivePathResolver>()

        val assembler = SyncAssembler(
            http = http,
            secrets = secrets,
            settings = settings,
            vault = InMemoryLocalVault(),
            stateStore = InMemorySyncStateStore(),
            remoteFactory = { client, root ->
                val resolver = DrivePathResolver(client, root)
                resolvers += resolver
                GoogleDriveStorage(client, resolver)
            },
            cipherFactory = { password, salt -> TestCiphers.forPassword(password, salt) },
        )

        val first = assembler.buildEngine()
        val second = assembler.buildEngine()
        assertNotNull(first)
        assertSame(first.first, second?.first)
        assertSame(first.second, second?.second)
        assertEquals(1, resolvers.size)

        settings.update { it.copy(rootFolder = "VaultB") }
        val third = assembler.buildEngine()
        assertNotNull(third)
        assertEquals(2, resolvers.size)
        assertNotSame(resolvers[0], resolvers[1])
        assertNotSame(first.second, third.second)

        assembler.clearKey()
        val fourth = assembler.buildEngine()
        assertNotNull(fourth)
        assertEquals(3, resolvers.size)
        assertNotSame(resolvers[1], resolvers[2])
        assertNotSame(third.second, fourth.second)
    }
}
