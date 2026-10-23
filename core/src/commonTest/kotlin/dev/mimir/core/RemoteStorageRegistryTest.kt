package dev.mimir.core

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class RemoteStorageRegistryTest {
    private class FakeProvider(override val id: String) : RemoteStorageProvider {
        override val displayName = id
        override val capabilities = StorageCapabilities(changeFeed = false)
        override fun create(config: Map<String, String>): RemoteStorage = InMemoryRemoteStorage()
    }

    private val registry = RemoteStorageRegistry()

    @Test
    fun `registered provider is found by id`() {
        val fake = FakeProvider("gdrive")
        registry.register(fake)
        assertSame(fake, registry["gdrive"])
    }

    @Test
    fun `unknown id returns null`() = assertNull(registry["nope"])

    @Test
    fun `duplicate id is rejected`() {
        registry.register(FakeProvider("gdrive"))
        assertFailsWith<IllegalArgumentException> { registry.register(FakeProvider("gdrive")) }
    }
}
