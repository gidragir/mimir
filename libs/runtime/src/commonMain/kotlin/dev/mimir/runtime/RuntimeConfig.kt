package dev.mimir.runtime

import java.nio.file.Path

/** Настройки, нужные [MimirRuntime] на любой платформе; откуда они берутся — дело [RuntimeSettingsStore]. */
data class RuntimeConfig(
    /** Каталог данных: индекс, токены, `account.json`, хэш канарейки. */
    val dataDir: Path,
    val vaultPath: String?,
    val syncIntervalSeconds: Long = 30,
    val syncParallelism: Int = 4,
    val rootFolder: String = "MimirVault",
)

/** Порт хранения настроек: демон — `config.json` и окружение, Android — [JsonRuntimeSettingsStore]. */
interface RuntimeSettingsStore {
    fun load(): RuntimeConfig

    /** Запоминает выбранный vault; следующий [load] его вернёт. */
    fun saveVaultPath(path: String)
}
