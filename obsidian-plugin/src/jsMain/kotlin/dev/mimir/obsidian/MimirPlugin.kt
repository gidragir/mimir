package dev.mimir.obsidian

import dev.mimir.core.StubSyncEngine
import dev.mimir.core.label

@JsExport
@JsName("default")
class MimirPlugin : Plugin() {
    private val engine = StubSyncEngine()

    override fun onload() {
        addStatusBarItem().textContent = engine.status.value.label()
    }
}
