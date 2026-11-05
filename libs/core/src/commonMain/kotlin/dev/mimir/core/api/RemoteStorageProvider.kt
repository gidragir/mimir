package dev.mimir.core

interface RemoteStorageProvider {
    val id: String
    val displayName: String
    val capabilities: StorageCapabilities
    fun create(config: Map<String, String>): RemoteStorage
}
