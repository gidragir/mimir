package dev.mimir.core.testing

import dev.mimir.core.LocalEntry
import dev.mimir.core.LocalVault
import dev.mimir.core.VaultChange
import dev.mimir.core.VaultFileId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlin.time.Clock
import kotlin.time.Instant

/** Vault в памяти для тестов. Состояние — под [lock]: движок обращается к vault из параллельных воркеров. */
class InMemoryLocalVault(
    private val clock: Clock = Clock.System,
) : LocalVault {
    private val lock = Mutex()

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

    /** Пути, чтение которых падает с [IOException] (тестовый хук). */
    val failReadFor: MutableSet<String> = mutableSetOf()

    /** Пути, запись которых падает с [IOException] (тестовый хук). */
    val failWriteFor: MutableSet<String> = mutableSetOf()

    override suspend fun list(): List<LocalEntry> = lock.withLock {
        files.values.map { it.toEntry() }
    }

    override suspend fun getEntry(relativePath: String): LocalEntry? = lock.withLock {
        files[normalize(relativePath)]?.toEntry()
    }

    override suspend fun read(id: VaultFileId): Source = lock.withLock {
        if (throwOnRead) throw IOException("Read failed")
        val path = idToPath[id] ?: throw NoSuchElementException("No such file: ${id.value}")
        if (path in failReadFor) throw IOException("Read failed: $path")
        val stored = files[path] ?: throw NoSuchElementException("No such file: ${id.value}")
        Buffer().also { it.write(stored.bytes) }
    }

    override suspend fun write(id: VaultFileId, data: Source) {
        val path = lock.withLock { idToPath[id] } ?: id.value
        write(path, data)
    }

    override suspend fun write(relativePath: String, data: Source): LocalEntry {
        if (throwOnWrite) throw IOException("Write failed")
        val normalized = normalize(relativePath)
        if (normalized in failWriteFor) throw IOException("Write failed: $normalized")
        // поток читается до захвата блокировки: он может идти из другого хранилища
        val bytes = data.readByteArray()
        return lock.withLock { store(normalized, bytes) }
    }

    private fun store(normalized: String, bytes: ByteArray): LocalEntry {
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

    override suspend fun delete(id: VaultFileId): Unit = lock.withLock {
        val path = idToPath.remove(id) ?: return@withLock
        files.remove(path)
        _changes.tryEmit(VaultChange.Deleted(id))
    }

    override suspend fun deleteByPath(relativePath: String): Unit = lock.withLock {
        val normalized = normalize(relativePath)
        val stored = files.remove(normalized) ?: return@withLock
        idToPath.remove(stored.id)
        _changes.tryEmit(VaultChange.Deleted(stored.id))
    }

    override fun changes(): Flow<VaultChange> = _changes.asSharedFlow()

    private fun normalize(p: String): String =
        p.replace('\\', '/').split('/').filter { it.isNotEmpty() }.joinToString("/")

    private fun StoredFile.toEntry() =
        LocalEntry(id, relativePath, bytes.size.toLong(), modifiedAt)
}
