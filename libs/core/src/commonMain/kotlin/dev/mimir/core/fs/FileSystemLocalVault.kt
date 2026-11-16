package dev.mimir.core.fs

import dev.mimir.core.LocalEntry
import dev.mimir.core.LocalVault
import dev.mimir.core.VaultChange
import dev.mimir.core.VaultFileId
import dev.mimir.core.toHex
import dev.mimir.core.randomNonce
import dev.mimir.core.rules.SyncRules
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Instant

class FileSystemLocalVault(
    val rootPath: Path,
    rules: SyncRules = SyncRules(),
    private val watcher: VaultWatcher? = null,
    /**
     * Где исполняется блокирующий файловый ввод-вывод. В common-коде `Dispatchers.IO` нет (JS/wasm),
     * поэтому JVM/Android-вызовы передают `Dispatchers.IO` явно.
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: Clock = Clock.System,
) : LocalVault {
    private val filter = VaultPathFilter(rules)

    @Volatile
    private var lastHiddenPaths: Set<String>? = null

    override suspend fun list(): List<LocalEntry> = withContext(ioDispatcher) {
        val result = ArrayList<LocalEntry>()
        val links = LinkedHashSet<String>()
        walk(rootPath, "", result, links)
        lastHiddenPaths = links
        result
    }

    override suspend fun hiddenPaths(): Set<String> = withContext(ioDispatcher) {
        lastHiddenPaths?.let { return@withContext it }
        val links = LinkedHashSet<String>()
        walk(rootPath, "", out = null, symbolicLinks = links)
        lastHiddenPaths = links
        links
    }

    override suspend fun getEntry(relativePath: String): LocalEntry? = withContext(ioDispatcher) {
        val normalized = normalize(relativePath)
        when {
            isExcluded(normalized) || hasSymbolicLink(normalized) -> null
            else -> {
                val file = absolute(normalized)
                SystemFileSystem.metadataOrNull(file)
                    ?.takeIf { it.isRegularFile }
                    ?.let { entryOf(normalized, file, it.size) }
            }
        }
    }

    /** Проверки и открытие — на [ioDispatcher]; последующее чтение идёт в корутине-потребителе. */
    override suspend fun read(id: VaultFileId): Source = withContext(ioDispatcher) {
        val path = absolute(requireNoSymbolicLink(pathOf(id)))
        if (SystemFileSystem.metadataOrNull(path)?.isRegularFile != true) {
            throw NoSuchElementException("No such file: ${id.value}")
        }
        SystemFileSystem.source(path).buffered()
    }

    override suspend fun write(id: VaultFileId, data: Source) {
        write(pathOf(id), data)
    }

    override suspend fun write(relativePath: String, data: Source): LocalEntry = withContext(ioDispatcher) {
        val normalized = requireNoSymbolicLink(normalize(relativePath))
        val target = absolute(normalized)
        val directory = target.parent ?: rootPath
        SystemFileSystem.createDirectories(directory)
        val temp = Path(directory, "$TEMP_PREFIX${randomNonce().toHex()}")
        try {
            SystemFileSystem.sink(temp).buffered().use { sink ->
                data.transferTo(sink)
                sink.flush()
            }
            watcher?.markWrittenBySync(normalized)
            SystemFileSystem.atomicMove(temp, target)
        } catch (e: Throwable) {
            runCatching { SystemFileSystem.delete(temp, mustExist = false) }
            throw e
        }
        entryOf(normalized, target, SystemFileSystem.metadataOrNull(target)?.size ?: 0L)
    }

    override suspend fun delete(id: VaultFileId) {
        deleteByPath(pathOf(id))
    }

    override suspend fun deleteByPath(relativePath: String) = withContext(ioDispatcher) {
        val normalized = requireNoSymbolicLink(normalize(relativePath))
        watcher?.markWrittenBySync(normalized)
        SystemFileSystem.delete(absolute(normalized), mustExist = false)
    }

    override suspend fun trash(id: VaultFileId) = withContext(ioDispatcher) {
        val normalized = requireNoSymbolicLink(normalize(pathOf(id)))
        val source = absolute(normalized)
        if (SystemFileSystem.metadataOrNull(source) == null) return@withContext

        val dt = clock.now().toLocalDateTime(TimeZone.UTC)
        val dateFolder = "${dt.year.toString().padStart(4, '0')}-" +
            "${(dt.month.ordinal + 1).toString().padStart(2, '0')}-" +
            dt.day.toString().padStart(2, '0')
        val target = resolveTrashTarget("$TRASH_DIR/$dateFolder/$normalized")
        target.parent?.let { SystemFileSystem.createDirectories(it) }
        watcher?.markWrittenBySync(normalized)
        SystemFileSystem.atomicMove(source, target)
    }

    override suspend fun purgeTrash(olderThan: Instant) = withContext(ioDispatcher) {
        val trashRoot = Path(rootPath, ".mimir", "trash")
        if (SystemFileSystem.metadataOrNull(trashRoot)?.isDirectory != true) return@withContext
        val cutoffDate = olderThan.toLocalDateTime(TimeZone.UTC).date
        for (child in SystemFileSystem.list(trashRoot)) {
            if (isSymbolicLink(child)) continue
            val dirDate = runCatching { LocalDate.parse(child.name) }.getOrNull() ?: continue
            if (dirDate < cutoffDate) {
                deleteRecursively(child)
            }
        }
    }

    private fun resolveTrashTarget(baseRelative: String): Path {
        val first = absolute(baseRelative)
        if (SystemFileSystem.metadataOrNull(first) == null) return first
        val slash = baseRelative.lastIndexOf('/')
        val dir = if (slash >= 0) baseRelative.substring(0, slash) else ""
        val filename = if (slash >= 0) baseRelative.substring(slash + 1) else baseRelative
        val dot = filename.lastIndexOf('.')
        val (stem, ext) = if (dot > 0) filename.substring(0, dot) to filename.substring(dot) else filename to ""
        var attempt = 2
        while (true) {
            val numbered = "$stem ($attempt)$ext"
            val candidateRel = if (dir.isNotEmpty()) "$dir/$numbered" else numbered
            val candidate = absolute(candidateRel)
            if (SystemFileSystem.metadataOrNull(candidate) == null) return candidate
            attempt++
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!isSymbolicLink(path) && SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
            for (child in SystemFileSystem.list(path)) {
                deleteRecursively(child)
            }
        }
        SystemFileSystem.delete(path, mustExist = false)
    }

    override fun changes(): Flow<VaultChange> = watcher?.start() ?: emptyFlow()

    /** Обходит vault, не заходя в исключённые пути и символьные ссылки; ссылки складываются в [symbolicLinks]. */
    private fun walk(dir: Path, relativeDir: String, out: MutableList<LocalEntry>?, symbolicLinks: MutableSet<String>?) {
        if (SystemFileSystem.metadataOrNull(dir)?.isDirectory != true) return
        for (child in SystemFileSystem.list(dir).sortedBy { it.name }) {
            val relative = if (relativeDir.isEmpty()) child.name else "$relativeDir/${child.name}"
            if (isExcluded(relative)) continue
            if (isSymbolicLink(child)) {
                symbolicLinks?.add(relative)
                continue
            }
            val metadata = SystemFileSystem.metadataOrNull(child) ?: continue
            when {
                metadata.isDirectory -> walk(child, relative, out, symbolicLinks)
                metadata.isRegularFile -> out?.add(entryOf(relative, child, metadata.size))
            }
        }
    }

    private fun entryOf(relativePath: String, file: Path, size: Long) = LocalEntry(
        id = VaultFileId(VAULT_ID_PREFIX + relativePath),
        relativePath = relativePath,
        size = size,
        modifiedAt = Instant.fromEpochMilliseconds(lastModifiedMillis(file)),
    )

    private fun isExcluded(relativePath: String): Boolean = filter.isExcluded(relativePath)

    /** Симлинк на любом уровне пути: vault не разворачивает ссылки (иначе в него попадают чужие каталоги, например `node_modules`). */
    private fun hasSymbolicLink(relativePath: String): Boolean {
        var cur = rootPath
        for (seg in relativePath.split('/')) {
            if (seg.isEmpty()) continue
            cur = Path(cur, seg)
            if (isSymbolicLink(cur)) return true
        }
        return false
    }

    private fun requireNoSymbolicLink(relativePath: String): String {
        require(!hasSymbolicLink(relativePath)) { "Refusing to follow a symbolic link: '$relativePath'" }
        return relativePath
    }

    private fun absolute(relativePath: String): Path = Path(rootPath, *relativePath.split('/').toTypedArray())

    private fun pathOf(id: VaultFileId): String = id.value.removePrefix(VAULT_ID_PREFIX)

    private fun normalize(relativePath: String): String {
        val segments = relativePath.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        require(segments.isNotEmpty() && ".." !in segments) { "Invalid vault path: '$relativePath'" }
        return segments.joinToString("/")
    }

    private companion object {
        const val TEMP_PREFIX = ".mimir_tmp_"
        const val TRASH_DIR = ".mimir/trash"
    }
}
