package dev.mimir.core

class RemoteStorageRegistry {
    private val providers = LinkedHashMap<String, RemoteStorageProvider>()

    fun register(provider: RemoteStorageProvider) {
        require(provider.id !in providers) { "Storage provider '${provider.id}' is already registered" }
        providers[provider.id] = provider
    }

    operator fun get(id: String): RemoteStorageProvider? = providers[id]

    fun all(): List<RemoteStorageProvider> = providers.values.toList()
}
