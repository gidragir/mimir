package dev.mimir.drive

import dev.mimir.core.RemoteEntry
import dev.mimir.core.RemotePath
import dev.mimir.core.RemoteStorage
import kotlinx.io.Source

class GoogleDriveStorage : RemoteStorage {
    private fun notImplemented(): Nothing = throw NotImplementedError("Google Drive adapter is not implemented yet")

    override suspend fun list(dir: RemotePath): List<RemoteEntry> = notImplemented()
    override suspend fun stat(path: RemotePath): RemoteEntry? = notImplemented()
    override suspend fun read(path: RemotePath): Source = notImplemented()
    override suspend fun write(path: RemotePath, data: Source): RemoteEntry = notImplemented()
    override suspend fun delete(path: RemotePath): Unit = notImplemented()
}
