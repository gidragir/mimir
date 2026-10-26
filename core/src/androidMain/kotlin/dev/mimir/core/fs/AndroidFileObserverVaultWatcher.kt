package dev.mimir.core.fs

import android.os.Build
import android.os.FileObserver
import dev.mimir.core.LocalEntry
import dev.mimir.core.VaultChange
import dev.mimir.core.VaultFileId
import dev.mimir.core.rules.SyncRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * [VaultWatcher] для Android на основе [FileObserver].
 * Рекурсивно отслеживает каталоги хранилища с подавлением собственного эха и дебаунсом событий.
 */
class AndroidFileObserverVaultWatcher(
    private val root: File,
    rules: SyncRules = SyncRules(),
    private val debounce: Duration = 1.seconds,
    echoTtl: Duration = 2.seconds,
    private val clock: Clock = Clock.System,
) : VaultWatcher {
    private val filter = VaultPathFilter(rules)
    private val echo = EchoFilter(echoTtl, clock)
    private val activeObservers = ConcurrentHashMap<File, FileObserver>()

    private class Pending(val firstKind: Int, var lastEventAt: Instant)

    private sealed interface WatcherEvent {
        data class FsEvent(val dir: File, val event: Int, val path: String?) : WatcherEvent
        data object Stop : WatcherEvent
    }

    override fun start(): Flow<VaultChange> = channelFlow {
        val eventChannel = Channel<WatcherEvent>(Channel.UNLIMITED)
        val pending = LinkedHashMap<String, Pending>()

        fun registerDir(dir: File) {
            if (activeObservers.containsKey(dir)) return
            val rel = if (dir == root) "" else relative(dir)
            if (rel.isNotEmpty() && filter.isExcluded(rel)) return

            val observer = createObserver(dir) { event, path ->
                eventChannel.trySend(WatcherEvent.FsEvent(dir, event, path))
            }
            activeObservers[dir] = observer
            observer.startWatching()
        }

        fun registerTree(dir: File) {
            dir.walkTopDown().filter { it.isDirectory }.forEach { subDir ->
                registerDir(subDir)
            }
        }

        try {
            registerTree(root)
            val tick = (debounce / 4).coerceIn(10.milliseconds, 250.milliseconds)
            while (isActive) {
                val item = withTimeoutOrNull(tick) { eventChannel.receive() }
                if (item is WatcherEvent.FsEvent) {
                    handleEvent(item.dir, item.event, item.path, ::registerTree, pending)
                } else if (item is WatcherEvent.Stop) {
                    break
                }
                flushDue(pending) { send(it) }
            }
        } finally {
            activeObservers.values.forEach { runCatching { it.stopWatching() } }
            activeObservers.clear()
        }
    }.flowOn(Dispatchers.IO)

    override fun stop() {
        activeObservers.values.forEach { runCatching { it.stopWatching() } }
        activeObservers.clear()
    }

    override fun markWrittenBySync(relativePath: String) = echo.mark(relativePath)

    private fun relative(file: File): String =
        file.relativeTo(root).invariantSeparatorsPath

    private fun handleEvent(
        dir: File,
        event: Int,
        path: String?,
        registerTree: (File) -> Unit,
        pending: MutableMap<String, Pending>,
    ) {
        if (path == null) {
            if ((event and (FileObserver.DELETE_SELF or FileObserver.MOVE_SELF)) != 0) {
                activeObservers.remove(dir)?.let { runCatching { it.stopWatching() } }
            }
            return
        }

        val target = File(dir, path)
        val rel = relative(target)
        if (filter.isExcluded(rel) || echo.isRecent(rel)) return

        val isCreate = (event and FileObserver.CREATE) != 0 || (event and FileObserver.MOVED_TO) != 0
        val isDelete = (event and FileObserver.DELETE) != 0 || (event and FileObserver.MOVED_FROM) != 0
        val isModify = (event and FileObserver.MODIFY) != 0 || (event and FileObserver.CLOSE_WRITE) != 0

        if (isCreate && target.isDirectory) {
            registerTree(target)
            return
        }

        if (isDelete && !target.exists()) {
            activeObservers.remove(target)?.let { runCatching { it.stopWatching() } }
        }

        if (target.isDirectory && !isDelete) return

        val kind = when {
            isCreate -> FileObserver.CREATE
            isDelete -> FileObserver.DELETE
            isModify -> FileObserver.MODIFY
            else -> return
        }

        val now = clock.now()
        val existing = pending[rel]
        if (existing != null) {
            existing.lastEventAt = now
        } else {
            pending[rel] = Pending(kind, now)
        }
    }

    private suspend fun flushDue(pending: MutableMap<String, Pending>, emit: suspend (VaultChange) -> Unit) {
        val now = clock.now()
        val due = pending.filterValues { now - it.lastEventAt >= debounce }
        for ((rel, item) in due) {
            pending.remove(rel)
            val file = File(root, rel)
            val id = VaultFileId(VAULT_ID_PREFIX + rel)
            if (file.isFile) {
                val entry = LocalEntry(
                    id = id,
                    relativePath = rel,
                    size = file.length(),
                    modifiedAt = Instant.fromEpochMilliseconds(file.lastModified())
                )
                emit(if (item.firstKind == FileObserver.CREATE) VaultChange.Created(id, entry) else VaultChange.Modified(id, entry))
            } else if (item.firstKind != FileObserver.CREATE) {
                emit(VaultChange.Deleted(id))
            }
        }
    }

    private fun createObserver(dir: File, onEventCallback: (Int, String?) -> Unit): FileObserver {
        val mask = FileObserver.CREATE or
            FileObserver.DELETE or
            FileObserver.MODIFY or
            FileObserver.MOVED_FROM or
            FileObserver.MOVED_TO or
            FileObserver.CLOSE_WRITE or
            FileObserver.DELETE_SELF or
            FileObserver.MOVE_SELF

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, mask) {
                override fun onEvent(event: Int, path: String?) {
                    onEventCallback(event, path)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(dir.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) {
                    onEventCallback(event, path)
                }
            }
        }
    }
}
