package dev.mimir.obsidian.vault

import dev.mimir.core.LocalEntry
import dev.mimir.core.LocalVault
import dev.mimir.core.VaultChange
import dev.mimir.core.VaultFileId
import dev.mimir.core.fs.EchoFilter
import dev.mimir.obsidian.platform.FsStat
import dev.mimir.obsidian.platform.ResourceStreamFn
import dev.mimir.obsidian.platform.VaultEvent
import dev.mimir.obsidian.platform.VaultFs
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Реализация [LocalVault] поверх файловой системы Obsidian ([VaultFs]).
 * Чтение файлов выполняется потоком через [ResourceStreamFn], запись — через временный файл
 * в той же директории (`.name.mimir-tmp`) блоками [writeChunkBytes] с атомарным rename.
 */
class ObsidianLocalVault(
    private val fs: VaultFs,
    private val resources: ResourceStreamFn,
    private val configDir: String,
    private val events: Flow<VaultEvent>,
    private val echo: EchoFilter,
    private val clock: Clock = Clock.System,
    private val writeChunkBytes: Int = 1 shl 20, // 1 MiB
) : LocalVault {

    private val normalizedConfigDir = configDir.trim('/')

    private fun isExcluded(relativePath: String): Boolean {
        val norm = relativePath.trim('/')
        if (normalizedConfigDir.isNotEmpty()) {
            if (norm == normalizedConfigDir || norm.startsWith("$normalizedConfigDir/")) return true
        }
        if (norm.endsWith(".mimir-tmp")) return true
        return false
    }

    override suspend fun hiddenPaths(): Set<String> =
        if (normalizedConfigDir.isEmpty()) emptySet() else setOf(normalizedConfigDir)

    override suspend fun list(): List<LocalEntry> {
        val pendingFiles = mutableListOf<String>()
        val queue = ArrayDeque<String>()
        queue.add("")

        while (queue.isNotEmpty()) {
            val currentDir = queue.removeFirst()
            val (files, folders) = fs.list(currentDir)

            for (file in files) {
                val norm = file.trim('/')
                if (!isExcluded(norm)) {
                    pendingFiles.add(norm)
                }
            }

            for (folder in folders) {
                val norm = folder.trim('/')
                if (!isExcluded(norm)) {
                    queue.add(norm)
                }
            }
        }

        val result = ArrayList<LocalEntry>(pendingFiles.size)
        for (batch in pendingFiles.chunked(STAT_BATCH_SIZE)) {
            val entries = coroutineScope {
                batch.map { norm ->
                    async {
                        val stat = fs.stat(norm)
                        if (stat != null && stat.type == "file") {
                            LocalEntry(
                                id = VaultFileId(norm),
                                relativePath = norm,
                                size = stat.size,
                                modifiedAt = Instant.fromEpochMilliseconds(stat.mtime),
                            )
                        } else {
                            null
                        }
                    }
                }.awaitAll()
            }
            for (entry in entries) {
                if (entry != null) {
                    result.add(entry)
                }
            }
        }

        return result
    }

    override suspend fun getEntry(relativePath: String): LocalEntry? {
        val norm = relativePath.trim('/')
        if (isExcluded(norm)) return null
        val stat = fs.stat(norm) ?: return null
        if (stat.type != "file") return null
        return LocalEntry(
            id = VaultFileId(norm),
            relativePath = norm,
            size = stat.size,
            modifiedAt = Instant.fromEpochMilliseconds(stat.mtime),
        )
    }

    override suspend fun read(id: VaultFileId): Source {
        return resources.open(id.value)
    }

    override suspend fun write(id: VaultFileId, data: Source) {
        write(id.value, data)
    }

    override suspend fun write(relativePath: String, data: Source): LocalEntry {
        val norm = relativePath.trim('/')
        val tmpPath = tmpPathFor(norm)

        ensureParentDir(norm)

        var completed = false
        try {
            // Первый блок пишется через writeBinary
            val firstChunk = readChunk(data, writeChunkBytes)
            fs.writeBinary(tmpPath, firstChunk)

            // Остальные блоки дописываются через appendBinary
            while (!data.exhausted()) {
                val chunk = readChunk(data, writeChunkBytes)
                fs.appendBinary(tmpPath, chunk)
            }

            echo.mark(norm)

            // Мобильные адаптеры могут не перезаписывать существующий файл при rename: удаляем целевой перед rename
            if (fs.exists(norm)) {
                fs.remove(norm)
            }
            fs.rename(tmpPath, norm)
            completed = true
        } finally {
            if (!completed) {
                withContext(NonCancellable) {
                    try {
                        if (fs.exists(tmpPath)) {
                            fs.remove(tmpPath)
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
        }

        val stat = if (isExcluded(norm)) null else fs.stat(norm)?.takeIf { it.type == "file" }
        return LocalEntry(
            id = VaultFileId(norm),
            relativePath = norm,
            size = stat?.size ?: 0L,
            modifiedAt = stat?.let { Instant.fromEpochMilliseconds(it.mtime) } ?: clock.now(),
        )
    }

    override suspend fun delete(id: VaultFileId) {
        deleteByPath(id.value)
    }

    override suspend fun deleteByPath(relativePath: String) {
        val norm = relativePath.trim('/')
        if (fs.exists(norm)) {
            echo.mark(norm)
            fs.remove(norm)
        }
    }

    override fun changes(): Flow<VaultChange> = channelFlow {
        events.collect { event ->
            when (event) {
                is VaultEvent.Upsert -> {
                    val norm = event.path.trim('/')
                    if (!isExcluded(norm) && !echo.isRecent(norm)) {
                        val entry = getEntry(norm)
                        if (entry != null) {
                            send(VaultChange.Modified(entry.id, entry))
                        }
                    }
                }
                is VaultEvent.Removed -> {
                    val norm = event.path.trim('/')
                    if (!isExcluded(norm) && !echo.isRecent(norm)) {
                        send(VaultChange.Deleted(VaultFileId(norm)))
                    }
                }
                is VaultEvent.Renamed -> {
                    val fromNorm = event.from.trim('/')
                    val toNorm = event.to.trim('/')

                    if (!isExcluded(fromNorm) && !echo.isRecent(fromNorm)) {
                        send(VaultChange.Deleted(VaultFileId(fromNorm)))
                    }
                    if (!isExcluded(toNorm) && !echo.isRecent(toNorm)) {
                        val entry = getEntry(toNorm)
                        if (entry != null) {
                            send(VaultChange.Modified(entry.id, entry))
                        }
                    }
                }
            }
        }
    }

    private suspend fun ensureParentDir(path: String) {
        val slash = path.lastIndexOf('/')
        if (slash <= 0) return
        val parent = path.substring(0, slash)
        val parts = parent.split('/')
        var current = ""
        for (part in parts) {
            if (part.isEmpty()) continue
            current = if (current.isEmpty()) part else "$current/$part"
            if (!fs.exists(current)) {
                fs.mkdir(current)
            }
        }
    }

    private fun tmpPathFor(path: String): String {
        val idx = path.lastIndexOf('/')
        return if (idx == -1) {
            ".$path.mimir-tmp"
        } else {
            val dir = path.substring(0, idx)
            val name = path.substring(idx + 1)
            "$dir/.$name.mimir-tmp"
        }
    }

    private fun readChunk(source: Source, chunkSize: Int): ByteArray {
        val buffer = Buffer()
        var readTotal = 0L
        while (!source.exhausted() && readTotal < chunkSize) {
            val toRead = chunkSize - readTotal
            val read = source.readAtMostTo(buffer, toRead)
            if (read == -1L) break
            readTotal += read
        }
        return buffer.readByteArray()
    }

    private companion object {
        const val STAT_BATCH_SIZE = 64
    }
}
