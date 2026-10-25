package dev.mimir.core

import kotlinx.io.Source
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * Декоратор-счётчик над [RemoteStorage]: для тестов бюджета запросов (сколько вызовов к облаку на прогон).
 * Считаются только вызовы снаружи; [listAll] делегируется в `inner.listAll` целиком, поэтому его
 * внутренний обход через `list` не попадает в [listCalls]. Ленту изменений не реализует.
 * Счётчики атомарны: движок обращается к хранилищу из параллельных воркеров.
 */
@OptIn(ExperimentalAtomicApi::class)
class CountingRemoteStorage(private val inner: RemoteStorage) : RemoteStorage {
    private val listAllCallsCount = AtomicInt(0)
    val listAllCalls: Int get() = listAllCallsCount.load()
    private val listCallsCount = AtomicInt(0)
    val listCalls: Int get() = listCallsCount.load()
    private val statCallsCount = AtomicInt(0)
    val statCalls: Int get() = statCallsCount.load()
    private val readCallsCount = AtomicInt(0)
    val readCalls: Int get() = readCallsCount.load()
    private val writeCallsCount = AtomicInt(0)
    val writeCalls: Int get() = writeCallsCount.load()
    private val deleteCallsCount = AtomicInt(0)
    val deleteCalls: Int get() = deleteCallsCount.load()

    fun reset() {
        listAllCallsCount.store(0)
        listCallsCount.store(0)
        statCallsCount.store(0)
        readCallsCount.store(0)
        writeCallsCount.store(0)
        deleteCallsCount.store(0)
    }

    override suspend fun list(dir: RemotePath): List<RemoteEntry> {
        listCallsCount.incrementAndFetch()
        return inner.list(dir)
    }

    override suspend fun listAll(
        root: RemotePath,
        onProgress: (folders: Int, files: Int) -> Unit,
    ): List<RemoteEntry> {
        listAllCallsCount.incrementAndFetch()
        return inner.listAll(root, onProgress)
    }

    override suspend fun stat(path: RemotePath): RemoteEntry? {
        statCallsCount.incrementAndFetch()
        return inner.stat(path)
    }

    override suspend fun read(path: RemotePath): Source {
        readCallsCount.incrementAndFetch()
        return inner.read(path)
    }

    override suspend fun write(path: RemotePath, data: Source): RemoteEntry {
        writeCallsCount.incrementAndFetch()
        return inner.write(path, data)
    }

    override suspend fun delete(path: RemotePath) {
        deleteCallsCount.incrementAndFetch()
        inner.delete(path)
    }
}
