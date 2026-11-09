package dev.mimir.obsidian

import dev.mimir.core.StubSyncEngine
import dev.mimir.core.label
import dev.mimir.obsidian.update.PluginUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import dev.mimir.obsidian.diag.runDiagnostics
import dev.mimir.obsidian.diag.showDiagnostics
import kotlin.js.json

@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
@JsName("default")
class MimirPlugin(app: App, manifest: PluginManifest) : Plugin(app, manifest) {
    private val engine = StubSyncEngine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onload() {
        addStatusBarItem().textContent = engine.status.value.label()
        addCommand(
            json(
                "id" to "mimir-diagnostics",
                "name" to "Diagnostics",
                "callback" to {
                    scope.launch {
                        val lines = try {
                            runDiagnostics(app)
                        } catch (e: Throwable) {
                            listOf("FAIL ${e.message}")
                        }
                        showDiagnostics(app, lines)
                    }
                },
            ),
        )
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
