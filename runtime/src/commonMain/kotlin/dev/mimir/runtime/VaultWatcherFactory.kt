package dev.mimir.runtime

import dev.mimir.core.fs.VaultWatcher
import dev.mimir.core.rules.SyncRules
import kotlinx.io.files.Path
import kotlin.time.Duration

/** Платформенный наблюдатель vault: JVM — `NioWatchServiceVaultWatcher`, Android — `AndroidFileObserverVaultWatcher`. */
fun interface VaultWatcherFactory {
    fun create(root: Path, rules: SyncRules, debounce: Duration): VaultWatcher
}
