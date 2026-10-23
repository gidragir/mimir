package dev.mimir.core.testing

import dev.mimir.core.LocalEntry
import dev.mimir.core.LocalVault
import dev.mimir.core.VaultChange
import dev.mimir.core.VaultFileId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlin.time.Clock
import kotlin.time.Instant

class InMemoryLocalVault(
    private val clock: Clock = Clock.System,
) : LocalVault {

    private class StoredFile(
        val id: VaultFileId,
        val relativePath: String,
        var bytes: ByteArray,
        var modifiedAt: Instant,
    )

    private val files = LinkedHashMap<String, StoredFile>()
    private val idToPath = LinkedHashMap<VaultFileId, String>()
    private var idCounter = 0

    private val _changes = MutableSharedFlow<VaultChange>(replay = 64, extraBufferCapacity = 64)

    var throwOnRead: Boolean = false
    var throwOnWrite: Boolean = false

    override suspend fun list(): List<LocalEntry> =
        files.values.map { it.toEntry() }

    override suspend fun getEntry(relativePath: String): LocalEntry? {
        val normalized = normalize(relativePath)
        return files[normalized]?.toEntry()
    }

    override suspend fun read(id: VaultFileId): Source {
        if (throwOnRead) throw IOException("Read failed")
        val path = idToPath[id] ?: throw NoSuchElementException("No such file: ${id.value}")
        val stored = files[path] ?: throw NoSuchElementException("No such file: ${id.value}")
        return Buffer().also { it.write(stored.bytes) }
    }

    override suspend fun write(id: VaultFileId, data: Source) {
        val path = idToPath[id] ?: id.value
        write(path, data)
    }

    override suspend fun write(relativePath: String, data: Source): LocalEntry {
        if (throwOnWrite) throw IOException("Write failed")
        val normalized = normalize(relativePath)
        val bytes = data.readByteArray()
        val now = clock.now()

        val existing = files[normalized]
        val entry = if (existing != null) {
            existing.bytes = bytes
            existing.modifiedAt = now
            val updated = existing.toEntry()
            _changes.tryEmit(VaultChange.Modified(existing.id, updated))
            updated
        } else {
            val id = VaultFileId("local-${++idCounter}")
            val stored = StoredFile(id, normalized, bytes, now)
            files[normalized] = stored
            idToPath[id] = normalized
            val created = stored.toEntry()
            _changes.tryEmit(VaultChange.Created(id, created))
            created
        }
        return entry
    }

    override suspend fun delete(id: VaultFileId) {
        val path = idToPath.remove(id) ?: return
        files.remove(path)
        _changes.tryEmit(VaultChange.Deleted(id))
    }

    override suspend fun deleteByPath(relativePath: String) {
        val normalized = normalize(relativePath)
        val stored = files.remove(normalized) ?: return
        idToPath.remove(stored.id)
        _changes.tryEmit(VaultChange.Deleted(stored.id))
    }

    override fun changes(): Flow<VaultChange> = _changes.asSharedFlow()

    private fun normalize(p: String): String =
        p.replace('\\', '/').split('/').filter { it.isNotEmpty() }.joinToString("/")

    private fun StoredFile.toEntry() =
        LocalEntry(id, relativePath, bytes.size.toLong(), modifiedAt)
}
