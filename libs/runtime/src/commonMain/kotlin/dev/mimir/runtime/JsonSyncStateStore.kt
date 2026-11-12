package dev.mimir.runtime

import dev.mimir.core.sync.SyncIndex
import dev.mimir.core.sync.SyncIndexJson
import dev.mimir.core.sync.SyncStateStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

class JsonSyncStateStore(
    val indexFile: Path,
    /** Где исполняется блокирующая запись и чтение индекса; JVM/Android передают `Dispatchers.IO`. */
    private val ioDispatcher: CoroutineDispatcher,
) : SyncStateStore {
    private val mutex = Mutex()

    override suspend fun load(): SyncIndex = mutex.withLock {
        withContext(ioDispatcher) {
            if (!Files.isRegularFile(indexFile)) return@withContext SyncIndex()
            // Повреждённый индекс не сбрасываем молча: пустой индекс вызвал бы полную пересверку.
            SyncIndexJson.decode(Files.readString(indexFile))
        }
    }

    override suspend fun save(index: SyncIndex) {
        mutex.withLock { withContext(ioDispatcher) { writeFileAtomically(indexFile, SyncIndexJson.encode(index)) } }
    }
}

