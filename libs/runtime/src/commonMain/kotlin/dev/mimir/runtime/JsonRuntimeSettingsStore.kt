package dev.mimir.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/** Настройки в `settings.json` каталога данных (Android: `context.filesDir`); запись атомарная. */
class JsonRuntimeSettingsStore(private val dataDir: Path) : RuntimeSettingsStore {
    private val file = dataDir.resolve(SETTINGS_FILE)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    override fun load(): RuntimeConfig = read().let {
        RuntimeConfig(dataDir, it.vaultPath, it.syncIntervalSeconds, it.syncParallelism, it.rootFolder)
    }

    @Synchronized
    override fun saveVaultPath(path: String) {
        writeFileAtomically(file, json.encodeToString(read().copy(vaultPath = path)))
    }

    private fun read(): SettingsFile =
        if (Files.isRegularFile(file)) json.decodeFromString(Files.readString(file)) else SettingsFile()

    @Serializable
    private data class SettingsFile(
        val vaultPath: String? = null,
        val syncIntervalSeconds: Long = 30,
        val syncParallelism: Int = 4,
        val rootFolder: String = "MimirVault",
    )

    private companion object {
        const val SETTINGS_FILE = "settings.json"
    }
}
