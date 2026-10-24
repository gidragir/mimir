package dev.mimir.core.fs

import dev.mimir.core.LocalEntry
import dev.mimir.core.LocalVault
import dev.mimir.core.VaultChange
import dev.mimir.core.VaultFileId
import dev.mimir.core.randomNonce
import dev.mimir.core.rules.SyncRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Instant

class FileSystemLocalVault(
    val rootPath: Path,
    rules: SyncRules = SyncRules(),
    private val watcher: VaultWatcher? = null,
) : LocalVault {
    private val filter = VaultPathFilter(rules)


    override suspend fun list(): List<LocalEntry> {
        val result = ArrayList<LocalEntry>()
        walk("", result)
        return result
    }

    override suspend fun getEntry(relativePath: String): LocalEntry? {
        val normalized = normalize(relativePath)
        if (isExcluded(normalized)) return null
        if (hasSymbolicLink(normalized)) return null
        val metadata = SystemFileSystem.metadataOrNull(absolute(normalized)) ?: return null
        if (!metadata.isRegularFile) return null
        return entryOf(normalized, metadata.size)
    }

    override suspend fun read(id: VaultFileId): Source {
        val path = absolute(requireNoSymbolicLink(pathOf(id)))
        if (SystemFileSystem.metadataOrNull(path)?.isRegularFile != true) {
            throw NoSuchElementException("No such file: ${id.value}")
        }
        return SystemFileSystem.source(path).buffered()
    }

    override suspend fun write(id: VaultFileId, data: Source) {
        write(pathOf(id), data)
    }

    override suspend fun write(relativePath: String, data: Source): LocalEntry {
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
        return entryOf(normalized, SystemFileSystem.metadataOrNull(target)?.size ?: 0L)
    }

    override suspend fun delete(id: VaultFileId) {
        deleteByPath(pathOf(id))
    }

    override suspend fun deleteByPath(relativePath: String) {
        val normalized = requireNoSymbolicLink(normalize(relativePath))
        watcher?.markWrittenBySync(normalized)
        SystemFileSystem.delete(absolute(normalized), mustExist = false)
    }

    override fun changes(): Flow<VaultChange> = watcher?.start() ?: emptyFlow()

    private fun walk(relativeDir: String, out: MutableList<LocalEntry>) {
        val dir = if (relativeDir.isEmpty()) rootPath else absolute(relativeDir)
        if (SystemFileSystem.metadataOrNull(dir)?.isDirectory != true) return
        for (child in SystemFileSystem.list(dir).sortedBy { it.name }) {
            val relative = if (relativeDir.isEmpty()) child.name else "$relativeDir/${child.name}"
            if (isExcluded(relative) || isSymbolicLink(child)) continue
            val metadata = SystemFileSystem.metadataOrNull(child) ?: continue
            when {
                metadata.isDirectory -> walk(relative, out)
                metadata.isRegularFile -> out += entryOf(relative, metadata.size)
            }
        }
    }

    private fun entryOf(relativePath: String, size: Long) = LocalEntry(
        id = VaultFileId(VAULT_ID_PREFIX + relativePath),
        relativePath = relativePath,
        size = size,
        modifiedAt = Instant.fromEpochMilliseconds(lastModifiedMillis(absolute(relativePath))),
    )

    private fun isExcluded(relativePath: String): Boolean = filter.isExcluded(relativePath)

    /** Симлинк на любом уровне пути: vault не разворачивает ссылки (иначе в него попадают чужие каталоги, например `node_modules`). */
    private fun hasSymbolicLink(relativePath: String): Boolean {
        val segments = relativePath.split('/')
        return segments.indices.any { isSymbolicLink(absolute(segments.take(it + 1).joinToString("/"))) }
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

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private companion object {
        const val TEMP_PREFIX = ".mimir_tmp_"
    }
}
