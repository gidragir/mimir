package dev.mimir.drive

import dev.mimir.core.RemoteStorage
import dev.mimir.core.RemoteStorageProvider
import dev.mimir.core.StorageCapabilities

object GoogleDriveStorageProvider : RemoteStorageProvider {
    override val id = "gdrive"
    override val displayName = "Google Drive"
    override val capabilities = StorageCapabilities(changeFeed = true)

    override fun create(config: Map<String, String>): RemoteStorage = GoogleDriveStorage()
}
