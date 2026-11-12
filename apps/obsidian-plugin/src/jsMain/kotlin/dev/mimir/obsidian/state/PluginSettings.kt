package dev.mimir.obsidian.state

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Настройки плагина Obsidian Mimir.
 */
@Serializable
data class PluginSettings(
    val rootFolder: String = "",
    val parallelism: Int = 2,
    val forceSyncWithDaemon: Boolean = false,
    val customClientId: String = "",
    val customClientSecret: String = "",
) {
    fun normalized(): PluginSettings =
        if (parallelism in 1..4) this else copy(parallelism = parallelism.coerceIn(1, 4))
}

/**
 * Персистентное хранилище настроек через [dev.mimir.obsidian.Plugin.loadData]/[dev.mimir.obsidian.Plugin.saveData].
 */
class PluginSettingsStore(
    private val load: suspend () -> dynamic,
    private val save: suspend (dynamic) -> Unit,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun get(): PluginSettings {
        val raw = try {
            load()
        } catch (_: Throwable) {
            null
        } ?: return PluginSettings()

        val jsonString = if (js("typeof raw === 'string'").unsafeCast<Boolean>()) {
            raw.unsafeCast<String>()
        } else {
            js("JSON.stringify")(raw) as? String
        }

        if (jsonString.isNullOrBlank() || jsonString == "null" || jsonString == "undefined") {
            return PluginSettings()
        }

        return try {
            json.decodeFromString<PluginSettings>(jsonString).normalized()
        } catch (_: Throwable) {
            PluginSettings()
        }
    }

    suspend fun update(f: (PluginSettings) -> PluginSettings): PluginSettings {
        val current = get()
        val updated = f(current).normalized()
        val jsonString = json.encodeToString(PluginSettings.serializer(), updated)
        val jsObj = js("JSON.parse")(jsonString)
        save(jsObj)
        return updated
    }
}
