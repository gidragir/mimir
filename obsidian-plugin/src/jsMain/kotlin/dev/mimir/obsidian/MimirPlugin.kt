package dev.mimir.obsidian

import dev.mimir.core.StubSyncEngine
import dev.mimir.core.label
import dev.mimir.obsidian.update.PluginUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

@JsExport
@JsName("default")
class MimirPlugin : Plugin() {
    private val engine = StubSyncEngine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onload() {
        addStatusBarItem().textContent = engine.status.value.label()
        // Обновления не должны ломать загрузку плагина ни при каких условиях
        try {
            PluginUpdater(this, scope).start()
        } catch (e: Throwable) {
            console.warn("Mimir: updater disabled: ${e.message}")
        }
    }

    override fun onunload() {
        scope.cancel()
    }
}
