package dev.mimir.runtime

import dev.mimir.core.Cipher
import dev.mimir.core.EncryptedRemoteStorage
import dev.mimir.core.fs.FileSystemLocalVault
import dev.mimir.core.fs.VaultWatcher
import dev.mimir.core.rules.SyncRules
import dev.mimir.core.sync.DefaultSyncEngine
import dev.mimir.drive.auth.AccessTokenSource
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.files.Path
import kotlin.time.Duration.Companion.seconds

/** Собранный движок с планировщиком; планировщик не запущен — запуск решает [MimirRuntime]. */
data class AssembledEngine(
    val engine: DefaultSyncEngine,
    val scheduler: SyncScheduler,
    val watcher: VaultWatcher,
    val remote: EncryptedRemoteStorage,
)

/**
 * Сборка движка синхронизации из платформенных портов (план → исполнение: решает [MimirRuntime], собирает этот класс).
 * Блокирующий IO — на [ioDispatcher], его передаёт точка входа.
 */
class EngineAssembler(
    private val httpClient: HttpClient,
    private val cloudFactory: CloudStorageFactory,
    private val watcherFactory: VaultWatcherFactory,
    private val ioDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val config: () -> RuntimeConfig,
) {
    /**
     * `null` — нет входа или облако не настроено. [InvalidPasswordException] — пароль не подходит к облаку,
     * [SyncNotConfiguredException] — без сети пароль не проверить.
     */
    suspend fun assemble(vaultPath: String, cipher: Cipher, tokenSource: AccessTokenSource): AssembledEngine? {
        if (!tokenSource.hasCredentials()) return null
        val config = config()
        val remote = cloudFactory.create(httpClient, tokenSource, config) ?: return null
        verifyOrInitCanary(remote, cipher, config.dataDir)

        val rules = SyncRules()
        val watcher = watcherFactory.create(Path(vaultPath), rules, WATCH_DEBOUNCE)
        val vault = FileSystemLocalVault(Path(vaultPath), rules, watcher, ioDispatcher = ioDispatcher)
        val encrypted = EncryptedRemoteStorage(remote, cipher, reservedRootNames = setOf(CANARY_FILENAME))
        val engine = DefaultSyncEngine(
            vault,
            encrypted,
            JsonSyncStateStore(config.dataDir.resolve(INDEX_FILE), ioDispatcher),
            rules = rules,
            parallelism = config.syncParallelism,
            dispatcher = ioDispatcher,
        )
        val scheduler = SyncScheduler(
            syncEngine = engine,
            syncInterval = config.syncIntervalSeconds.seconds,
            scope = scope,
            localChanges = vault.changes(),
            remoteFeed = encrypted,
        )
        return AssembledEngine(engine, scheduler, watcher, encrypted)
    }

    companion object {
        const val INDEX_FILE = "index.json"
        private val WATCH_DEBOUNCE = 1.seconds
    }
}
