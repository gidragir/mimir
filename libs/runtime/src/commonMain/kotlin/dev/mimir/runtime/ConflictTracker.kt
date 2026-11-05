package dev.mimir.runtime

import dev.mimir.core.LocalVault
import dev.mimir.core.fs.VaultWatcher
import dev.mimir.core.rules.SyncRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Список конфликтных копий в vault: пересчитывается по событиям ФС, без опроса в простое.
 * Vault для обхода — из [localVaultFactory] (отдельный экземпляр без watcher'а: правки разрешения конфликта
 * подхватит наблюдатель движка).
 */
internal class ConflictTracker(
    private val scope: CoroutineScope,
    private val watcherFactory: VaultWatcherFactory,
    private val localVaultFactory: (Path) -> LocalVault,
    private val scanInterval: Duration,
    private val vaultPath: () -> String?,
) {
    private val _conflicts = MutableStateFlow<List<ConflictFileEntry>>(emptyList())
    val conflicts: StateFlow<List<ConflictFileEntry>> = _conflicts.asStateFlow()

    private var job: Job? = null
    private var watcher: VaultWatcher? = null

    fun watch() {
        stop()
        val path = vaultPath() ?: return
        if (!Files.isDirectory(Paths.get(path))) return
        val watcher = watcherFactory.create(Path(path), SyncRules(), scanInterval.coerceAtMost(1.seconds))
        this.watcher = watcher
        // Только по событиям ФС: в простое vault не обходится. Разовый скан после регистрации ловит копии,
        // появившиеся до начала наблюдения. Сбой watcher'а (удалён корень, лимит inotify) не должен ронять runtime;
        // конфликты обновит запрос.
        job = scope.launch {
            watcher.start(onWatching = { runCatching { refresh() } })
                .catch { }
                .filter { ConflictFiles.parse(it.id.value) != null }
                .conflate()
                .collect { runCatching { refresh() } }
        }
    }

    fun stop() {
        job?.cancel()
        watcher?.stop()
        job = null
        watcher = null
    }

    suspend fun refresh(): List<ConflictFileEntry> {
        val found = vault()?.let { vault -> ConflictFiles.find(vault.list().map { it.relativePath }) } ?: emptyList()
        _conflicts.value = found
        return found
    }

    suspend fun resolve(originalPath: String, conflictPath: String, keepConflict: Boolean) {
        val vault = vault() ?: throw SyncNotConfiguredException("Vault path not configured")
        val parsed = ConflictFiles.parse(conflictPath.replace('\\', '/').trim('/'))
        require(parsed != null && parsed.originalPath == originalPath.replace('\\', '/').trim('/')) {
            "'$conflictPath' is not a conflicted copy of '$originalPath'"
        }
        val conflict = vault.getEntry(parsed.conflictPath) ?: throw NoSuchElementException("No such conflict: $conflictPath")
        if (keepConflict) {
            vault.read(conflict.id).use { source -> vault.write(parsed.originalPath, source) }
        }
        vault.deleteByPath(parsed.conflictPath)
        refresh()
    }

    private fun vault(): LocalVault? = vaultPath()?.let { localVaultFactory(Path(it)) }
}
