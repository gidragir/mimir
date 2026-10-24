package dev.mimir.core

import kotlinx.io.Source

/**
 * Декоратор-счётчик над [RemoteStorage]: для тестов бюджета запросов (сколько вызовов к облаку на прогон).
 * Считаются только вызовы снаружи; [listAll] делегируется в `inner.listAll` целиком, поэтому его
 * внутренний обход через `list` не попадает в [listCalls]. Ленту изменений не реализует.
 */
class CountingRemoteStorage(private val inner: RemoteStorage) : RemoteStorage {
    var listAllCalls: Int = 0
        private set
    var listCalls: Int = 0
        private set
    var statCalls: Int = 0
        private set
    var readCalls: Int = 0
        private set
    var writeCalls: Int = 0
        private set
    var deleteCalls: Int = 0
        private set

    fun reset() {
        listAllCalls = 0
        listCalls = 0
        statCalls = 0
        readCalls = 0
        writeCalls = 0
        deleteCalls = 0
    }

    override suspend fun list(dir: RemotePath): List<RemoteEntry> {
        listCalls++
        return inner.list(dir)
    }

    override suspend fun listAll(
        root: RemotePath,
        onProgress: (folders: Int, files: Int) -> Unit,
    ): List<RemoteEntry> {
        listAllCalls++
        return inner.listAll(root, onProgress)
    }

    override suspend fun stat(path: RemotePath): RemoteEntry? {
        statCalls++
        return inner.stat(path)
    }

    override suspend fun read(path: RemotePath): Source {
        readCalls++
        return inner.read(path)
    }

    override suspend fun write(path: RemotePath, data: Source): RemoteEntry {
        writeCalls++
        return inner.write(path, data)
    }

    override suspend fun delete(path: RemotePath) {
        deleteCalls++
        inner.delete(path)
    }
}
