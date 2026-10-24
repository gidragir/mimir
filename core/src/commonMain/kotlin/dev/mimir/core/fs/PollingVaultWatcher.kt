package dev.mimir.core.fs

import dev.mimir.core.LocalEntry
import dev.mimir.core.VaultChange
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Запасной вариант без WatchService: периодически сравнивает снимки состояния vault. */
class PollingVaultWatcher(
    private val scan: suspend () -> List<LocalEntry>,
    private val interval: Duration = 5.seconds,
    echoTtl: Duration = 2.seconds,
    clock: Clock = Clock.System,
) : VaultWatcher {
    private val echo = EchoFilter(echoTtl, clock)

    override fun start(): Flow<VaultChange> = flow {
        var previous = scan().associateBy { it.relativePath }
        while (true) {
            delay(interval)
            val current = scan().associateBy { it.relativePath }
            for ((path, entry) in current) {
                val before = previous[path]
                val change = when {
                    before == null -> VaultChange.Created(entry.id, entry)
                    before.size != entry.size || before.modifiedAt != entry.modifiedAt -> VaultChange.Modified(entry.id, entry)
                    else -> null
                }
                if (change != null && !echo.isRecent(path)) emit(change)
            }
            for ((path, entry) in previous) {
                if (path !in current && !echo.isRecent(path)) emit(VaultChange.Deleted(entry.id))
            }
            previous = current
        }
    }

    override fun stop() = Unit

    override fun markWrittenBySync(relativePath: String) = echo.mark(relativePath)
}
