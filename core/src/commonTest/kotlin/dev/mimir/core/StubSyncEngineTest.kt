package dev.mimir.core

import kotlin.test.Test
import kotlin.test.assertEquals

class StubSyncEngineTest {
    private val engine = StubSyncEngine()

    @Test
    fun `initial status is NotConfigured with label Mimir not connected`() =
        assertEquals("Mimir: not connected", engine.status.value.label())
}
