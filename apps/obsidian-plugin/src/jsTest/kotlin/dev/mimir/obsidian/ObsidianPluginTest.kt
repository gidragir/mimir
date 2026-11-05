package dev.mimir.obsidian

import dev.mimir.core.SyncStatus
import dev.mimir.core.label
import dev.mimir.core.update.UpdateHttpClient
import dev.mimir.core.update.UpdateHttpResponse
import dev.mimir.obsidian.update.ObsidianInPlaceApplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ObsidianPluginTest {
    @Test
    fun notConfiguredStatusLabel() {
        assertEquals("Mimir: not connected", SyncStatus.NotConfigured.label())
    }

    @Test
    fun inPlaceApplierIsConstructible() {
        val http = object : UpdateHttpClient {
            override suspend fun get(url: String, headers: Map<String, String>) =
                UpdateHttpResponse(200, emptyMap(), "")

            override suspend fun downloadStream(
                url: String,
                headers: Map<String, String>,
                onChunk: suspend (ByteArray, Long, Long) -> Unit,
            ): Result<Unit> = Result.success(Unit)
        }
        val adapter = js("({})").unsafeCast<DataAdapter>()
        assertNotNull(ObsidianInPlaceApplier(http, ".obsidian/plugins/mimir-sync", "mimir-sync", adapter))
    }
}
