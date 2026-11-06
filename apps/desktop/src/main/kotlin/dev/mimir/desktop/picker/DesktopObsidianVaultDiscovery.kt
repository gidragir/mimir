package dev.mimir.desktop.picker

import dev.mimir.ui.platform.VaultDiscovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class ObsidianConfig(val vaults: Map<String, ObsidianVaultEntry> = emptyMap())

@Serializable
private data class ObsidianVaultEntry(val path: String = "", val ts: Long = 0L, val open: Boolean = false)

private val json = Json { ignoreUnknownKeys = true }

/** Находит существующие хранилища Obsidian Desktop по системному `obsidian.json`. */
class DesktopObsidianVaultDiscovery(
    private val configFiles: () -> List<File> = ::defaultObsidianConfigFiles,
) : VaultDiscovery {
    override suspend fun findVaults(): List<String> = withContext(Dispatchers.IO) {
        configFiles()
            .filter { it.isFile }
            .flatMap { file ->
                try {
                    json.decodeFromString<ObsidianConfig>(file.readText()).vaults.values
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
            }
            .sortedWith(compareByDescending<ObsidianVaultEntry> { it.open }.thenByDescending { it.ts })
            .map { it.path }
            .filter { it.isNotBlank() && File(it).isDirectory }
            .distinct()
    }
}

internal fun defaultObsidianConfigFiles(
    osName: String = System.getProperty("os.name").orEmpty(),
    userHome: String = System.getProperty("user.home").orEmpty(),
    env: (String) -> String? = System::getenv,
): List<File> {
    val os = osName.lowercase()
    return when {
        os.contains("win") -> {
            val appData = env("APPDATA")?.takeIf { it.isNotBlank() } ?: File(userHome, "AppData/Roaming").path
            listOf(File(appData, "obsidian/obsidian.json"))
        }
        os.contains("mac") || os.contains("darwin") ->
            listOf(File(userHome, "Library/Application Support/obsidian/obsidian.json"))
        else -> listOfNotNull(
            env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { File(it, "obsidian/obsidian.json") },
            File(userHome, ".config/obsidian/obsidian.json"),
            File(userHome, ".var/app/md.obsidian.Obsidian/config/obsidian/obsidian.json"),
        ).distinct()
    }
}
