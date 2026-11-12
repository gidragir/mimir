package dev.mimir.obsidian.sync

import dev.mimir.core.CanaryVerifier
import dev.mimir.core.ChangeFeedStorage
import dev.mimir.core.EncryptedRemoteStorage
import dev.mimir.core.LocalVault
import dev.mimir.core.RcloneCipher
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
import kotlinx.io.Buffer

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
) : EngineSource {

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
            RcloneCipher.create(password, salt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return KeySetup.Failed(e.message ?: "Failed to initialize cipher")
        }

        val canaryPath = RemotePath(CanaryVerifier.CANARY_FILENAME)
        val canaryExists = try {
            remote.stat(canaryPath) != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return KeySetup.Failed(e.message ?: "Failed to check vault status")
        }

        val verifyResult = try {
            CanaryVerifier.verifyReadOnly(remote, cipher, knownCanaryHash = null)
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
                val isFirstDevice = !canaryExists
                if (isFirstDevice) {
                    try {
                        val canaryBuffer = Buffer().apply {
                            write(CanaryVerifier.CANARY_MAGIC.encodeToByteArray())
                        }
                        remote.write(canaryPath, cipher.encrypt(canaryBuffer))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        return KeySetup.Failed(e.message ?: "Failed to write vault canary")
                    }
                }
                secrets.saveKey(cipher.exportKeyBytes())
                secrets.saveCanaryHash(verifyResult.canaryHash)
                KeySetup.Ok(firstDevice = isFirstDevice)
            }
        }
    }

    override suspend fun buildEngine(): Pair<SyncEngine, ChangeFeedStorage>? {
        if (!isReady()) return null
        val keyBytes = secrets.loadKey() ?: return null
        if (keyBytes.size != 80) return null
        val settingsData = settings.get()
        val rootFolder = settingsData.rootFolder.trim()
        if (rootFolder.isEmpty()) return null

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
            parallelism = settingsData.parallelism.coerceIn(1, 16),
            dispatcher = Dispatchers.Default,
        )
        return engine to encryptedRemote
    }
}
