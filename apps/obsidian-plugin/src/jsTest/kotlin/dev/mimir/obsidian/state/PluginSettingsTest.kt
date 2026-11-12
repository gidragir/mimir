package dev.mimir.obsidian.state

import kotlinx.coroutines.test.runTest
import kotlin.js.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PluginSettingsTest {

    @Test
    fun settingsDefaults() = runTest {
        var savedData: dynamic = null
        val store = PluginSettingsStore(
            load = { null },
            save = { savedData = it },
        )

        val settings = store.get()
        assertEquals("", settings.rootFolder)
        assertEquals(2, settings.parallelism)
        assertFalse(settings.forceSyncWithDaemon)
        assertEquals("", settings.customClientId)
        assertEquals("", settings.customClientSecret)
    }

    @Test
    fun parallelismClampedTo1to4() = runTest {
        // Test loaded data with 0 -> 1
        var currentData: dynamic = json("parallelism" to 0)
        val storeLow = PluginSettingsStore(
            load = { currentData },
            save = { currentData = it },
        )
        assertEquals(1, storeLow.get().parallelism)

        // Test loaded data with 9 -> 4
        currentData = json("parallelism" to 9)
        val storeHigh = PluginSettingsStore(
            load = { currentData },
            save = { currentData = it },
        )
        assertEquals(4, storeHigh.get().parallelism)

        // Test update clamping
        storeLow.update { it.copy(parallelism = 0) }
        assertEquals(1, storeLow.get().parallelism)

        storeLow.update { it.copy(parallelism = 9) }
        assertEquals(4, storeLow.get().parallelism)
    }

    @Test
    fun unknownFieldsIgnored() = runTest {
        val currentData: dynamic = json(
            "rootFolder" to "ObsidianVault",
            "parallelism" to 3,
            "unexpectedFutureField" to "future-value",
            "extraObject" to json("nested" to 123),
        )

        val store = PluginSettingsStore(
            load = { currentData },
            save = {},
        )

        val settings = store.get()
        assertEquals("ObsidianVault", settings.rootFolder)
        assertEquals(3, settings.parallelism)
    }
}
