package dev.mimir.core.fs

import dev.mimir.core.LocalEntry
import dev.mimir.core.VaultChange
import dev.mimir.core.VaultFileId
import dev.mimir.core.rules.SyncRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.nio.file.ClosedWatchServiceException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class NioWatchServiceVaultWatcher(
    private val root: Path,
    rules: SyncRules = SyncRules(),
    private val debounce: Duration = 1.seconds,
    echoTtl: Duration = 2.seconds,
    private val clock: Clock = Clock.System,
) : VaultWatcher {
    private val filter = VaultPathFilter(rules)
    private val echo = EchoFilter(echoTtl, clock)
    private val services = ConcurrentHashMap.newKeySet<WatchService>()

    private class Pending(val firstKind: java.nio.file.WatchEvent.Kind<*>, var lastEventAt: Instant)

    override fun start(): Flow<VaultChange> = channelFlow {
        val service = root.fileSystem.newWatchService()
        services += service
        val dirs = HashMap<WatchKey, Path>()
        val pending = LinkedHashMap<String, Pending>()
        try {
            registerTree(service, root, dirs, pending)
            val tick = (debounce / 4).coerceIn(10.milliseconds, 250.milliseconds)
            while (isActive) {
                val key = service.poll(tick.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                if (key != null) {
                    val dir = dirs[key]
                    if (dir != null) handleEvents(service, key, dir, dirs, pending)
                    if (!key.reset()) dirs.remove(key)
                }
                flushDue(pending) { send(it) }
            }
        } catch (_: ClosedWatchServiceException) {
            // stop(): наблюдение завершено
        } catch (_: NoSuchFileException) {
            // директория удалена во время наблюдения
        } catch (_: java.io.IOException) {
            // ошибка ввода-вывода или закрытие сервиса
        } finally {
            services -= service
            runCatching { service.close() }
        }
    }.flowOn(Dispatchers.IO)

    override fun stop() {
        services.forEach { runCatching { it.close() } }
    }

    override fun markWrittenBySync(relativePath: String) = echo.mark(relativePath)

    private fun relative(path: Path): String = root.relativize(path).joinToString("/") { it.toString() }

    private fun registerTree(service: WatchService, start: Path, dirs: MutableMap<WatchKey, Path>, pending: MutableMap<String, Pending>) {
        Files.walk(start).use { stream ->
            stream.filter { Files.isDirectory(it) }.forEach { dir ->
                val rel = if (dir == root) "" else relative(dir)
                if (rel.isNotEmpty() && filter.isExcluded(rel)) return@forEach
                try {
                    dirs[dir.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)] = dir
                } catch (e: NoSuchFileException) {
                    if (dir == root) throw e // подкаталог мог исчезнуть между обходом и регистрацией
                }
            }
            // файлы, появившиеся в новом каталоге до его регистрации
            if (start != root) {
                Files.walk(start).use { files ->
                    files.filter { Files.isRegularFile(it) }.forEach { file ->
                        track(relative(file), ENTRY_CREATE, pending)
                    }
                }
            }
        }
    }

    private fun handleEvents(
        service: WatchService,
        key: WatchKey,
        dir: Path,
        dirs: MutableMap<WatchKey, Path>,
        pending: MutableMap<String, Pending>,
    ) {
        for (event in key.pollEvents()) {
            val kind = event.kind()
            if (kind == OVERFLOW) continue
            val full = dir.resolve(event.context() as Path)
            if (kind == ENTRY_CREATE && Files.isDirectory(full)) {
                if (!filter.isExcluded(relative(full))) registerTree(service, full, dirs, pending)
                continue
            }
            if (kind != ENTRY_DELETE && Files.isDirectory(full)) continue
            if (full in dirs.values) continue
            track(relative(full), kind, pending)
        }
    }

    private fun track(rel: String, kind: java.nio.file.WatchEvent.Kind<*>, pending: MutableMap<String, Pending>) {
        if (filter.isExcluded(rel) || echo.isRecent(rel)) return
        val now = clock.now()
        val existing = pending[rel]
        if (existing != null) existing.lastEventAt = now else pending[rel] = Pending(kind, now)
    }

    private suspend fun flushDue(pending: MutableMap<String, Pending>, emit: suspend (VaultChange) -> Unit) {
        val now = clock.now()
        val due = pending.filterValues { now - it.lastEventAt >= debounce }
        for ((rel, item) in due) {
            pending.remove(rel)
            val file = root.resolve(rel)
            val id = VaultFileId(VAULT_ID_PREFIX + rel)
            if (Files.isRegularFile(file)) {
                val entry = LocalEntry(id, rel, Files.size(file), Instant.fromEpochMilliseconds(Files.getLastModifiedTime(file).toMillis()))
                emit(if (item.firstKind == ENTRY_CREATE) VaultChange.Created(id, entry) else VaultChange.Modified(id, entry))
            } else if (item.firstKind != ENTRY_CREATE) {
                emit(VaultChange.Deleted(id))
            }
        }
    }
}
