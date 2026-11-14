package dev.mimir.obsidian.sync

import dev.mimir.core.CanaryVerifier
import dev.mimir.core.ChangeFeedStorage
import dev.mimir.core.EncryptedRemoteStorage
import dev.mimir.core.LocalVault
import dev.mimir.core.RcloneCipher
import dev.mimir.core.RemoteEntry
import dev.mimir.core.RemotePath
import dev.mimir.core.RemoteStorage
import dev.mimir.core.SyncEngine
import dev.mimir.core.sync.DefaultSyncEngine
import dev.mimir.core.sync.SyncStateStore
import dev.mimir.drive.GoogleDriveStorage
import dev.mimir.drive.auth.GoogleAuthClients
import dev.mimir.drive.client.DriveHttpClient
import dev.mimir.drive.path.DrivePathResolver
import dev.mimir.obsidian.state.DeviceSecretStore
import dev.mimir.obsidian.state.PluginSettingsStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.io.Source

sealed interface KeySetup {
    data class Ok(val firstDevice: Boolean) : KeySetup
    data class WrongPassword(val message: String) : KeySetup
    data class Failed(val message: String) : KeySetup
}

interface EngineSource {
    suspend fun buildEngine(): Pair<SyncEngine, ChangeFeedStorage>?
}

class SyncAssembler(
    private val http: HttpClient,
    private val secrets: DeviceSecretStore,
    private val settings: PluginSettingsStore,
    private val vault: LocalVault,
    private val stateStore: SyncStateStore,
    private val remoteFactory: (DriveHttpClient, String) -> RemoteStorage = { c, root ->
        GoogleDriveStorage(c, DrivePathResolver(c, root), downloadChunkBytes = 8L * 1024 * 1024)
    },
    private val cipherFactory: suspend (String, String?) -> RcloneCipher = { password, salt ->
        RcloneCipher.create(password, salt)
    },
) : EngineSource {

    private class AssembledSync(
        val rootFolder: String,
        val parallelism: Int,
        val customClientId: String,
        val customClientSecret: String,
        val keyBytes: ByteArray,
        val pair: Pair<SyncEngine, ChangeFeedStorage>,
    ) {
        fun matches(
            rootFolder: String,
            parallelism: Int,
            customClientId: String,
            customClientSecret: String,
            keyBytes: ByteArray,
        ): Boolean =
            this.rootFolder == rootFolder &&
                this.parallelism == parallelism &&
                this.customClientId == customClientId &&
                this.customClientSecret == customClientSecret &&
                this.keyBytes.contentEquals(keyBytes)
    }

    private class CanaryWriteTracker(
        private val delegate: RemoteStorage,
    ) : RemoteStorage by delegate {
        var wroteCanary: Boolean = false
            private set

        override suspend fun write(path: RemotePath, data: Source): RemoteEntry {
            if (path.value == CanaryVerifier.CANARY_FILENAME) {
                wroteCanary = true
            }
            return delegate.write(path, data)
        }
    }

    private var assembledEngine: AssembledSync? = null

    fun clearKey() {
        assembledEngine = null
    }

    suspend fun isReady(): Boolean {
        if (secrets.load() == null) return false
        val key = secrets.loadKey() ?: return false
        if (key.size != 80) return false
        return settings.get().rootFolder.trim().isNotEmpty()
    }

    suspend fun setupKey(password: String, salt: String?): KeySetup {
        if (secrets.load() == null) {
            return KeySetup.Failed("Not signed in")
        }
        val settingsData = settings.get()
        val rootFolder = settingsData.rootFolder.trim()
        if (rootFolder.isEmpty()) {
            return KeySetup.Failed("Root folder not configured")
        }

        val client = DriveHttpClient(
            httpClient = http,
            tokenStorage = secrets,
            authClients = GoogleAuthClients(desktop = null, device = deviceAuthClient(http, settingsData)),
        )
        val remote = remoteFactory(client, rootFolder)

        val cipher = try {
            cipherFactory(password, salt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return KeySetup.Failed(e.message ?: "Failed to initialize cipher")
        }

        val trackedRemote = CanaryWriteTracker(remote)
        val verifyResult = try {
            CanaryVerifier.verifyOrInit(trackedRemote, cipher, knownCanaryHash = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return KeySetup.Failed(e.message ?: "Failed to verify vault password")
        }

        return when (verifyResult) {
            is CanaryVerifier.CanaryVerifyResult.Invalid -> {
                KeySetup.WrongPassword(verifyResult.reason)
            }
            is CanaryVerifier.CanaryVerifyResult.Valid -> {
                secrets.saveKey(cipher.exportKeyBytes())
                secrets.saveCanaryHash(verifyResult.canaryHash)
                clearKey()
                KeySetup.Ok(firstDevice = trackedRemote.wroteCanary)
            }
        }
    }

    override suspend fun buildEngine(): Pair<SyncEngine, ChangeFeedStorage>? {
        if (!isReady()) {
            clearKey()
            return null
        }
        val keyBytes = secrets.loadKey() ?: return null
        if (keyBytes.size != 80) {
            clearKey()
            return null
        }
        val settingsData = settings.get()
        val rootFolder = settingsData.rootFolder.trim()
        if (rootFolder.isEmpty()) {
            clearKey()
            return null
        }
        val parallelism = settingsData.parallelism.coerceIn(1, 16)
        val cached = assembledEngine
        if (cached != null && cached.matches(
                rootFolder = rootFolder,
                parallelism = parallelism,
                customClientId = settingsData.customClientId,
                customClientSecret = settingsData.customClientSecret,
                keyBytes = keyBytes,
            )
        ) {
            return cached.pair
        }

        val client = DriveHttpClient(
            httpClient = http,
            tokenStorage = secrets,
            authClients = GoogleAuthClients(desktop = null, device = deviceAuthClient(http, settingsData)),
        )
        val baseRemote = remoteFactory(client, rootFolder)
        val cipher = RcloneCipher.fromKeyBytes(keyBytes)
        val encryptedRemote = EncryptedRemoteStorage(
            inner = baseRemote,
            cipher = cipher,
            reservedRootNames = setOf(CanaryVerifier.CANARY_FILENAME),
        )
        val engine = DefaultSyncEngine(
            localVault = vault,
            remoteStorage = encryptedRemote,
            stateStore = stateStore,
            parallelism = parallelism,
            dispatcher = Dispatchers.Default,
        )
        val pair = engine to encryptedRemote
        assembledEngine = AssembledSync(
            rootFolder = rootFolder,
            parallelism = parallelism,
            customClientId = settingsData.customClientId,
            customClientSecret = settingsData.customClientSecret,
            keyBytes = keyBytes.copyOf(),
            pair = pair,
        )
        return pair
    }
}
