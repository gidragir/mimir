package dev.mimir.desktop.picker

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopObsidianVaultDiscoveryTest {
    @Test
    fun `parses obsidian json sorting open and recent vaults first and filtering missing dirs`() = runTest {
        val tempRoot = Files.createTempDirectory("mimir-obsidian-test").toFile()
        try {
            val vaultOld = File(tempRoot, "OldVault").apply { mkdirs() }
            val vaultRecent = File(tempRoot, "RecentVault").apply { mkdirs() }
            val vaultOpen = File(tempRoot, "OpenVault").apply { mkdirs() }
            val missingPath = File(tempRoot, "DeletedVault").absolutePath

            val configFile = File(tempRoot, "obsidian.json")
            configFile.writeText(
                """
                {
                  "vaults": {
                    "1": { "path": ${quoteJson(vaultOld.absolutePath)}, "ts": 100 },
                    "2": { "path": ${quoteJson(missingPath)}, "ts": 999, "open": true },
                    "3": { "path": ${quoteJson(vaultRecent.absolutePath)}, "ts": 500 },
                    "4": { "path": ${quoteJson(vaultOpen.absolutePath)}, "ts": 200, "open": true }
                  }
                }
                """.trimIndent(),
            )

            val discovery = DesktopObsidianVaultDiscovery(configFiles = { listOf(configFile) })
            assertEquals(
                listOf(vaultOpen.absolutePath, vaultRecent.absolutePath, vaultOld.absolutePath),
                discovery.findVaults(),
            )
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `ignores malformed or missing obsidian json files without failing`() = runTest {
        val tempRoot = Files.createTempDirectory("mimir-obsidian-broken").toFile()
        try {
            val broken = File(tempRoot, "broken.json").apply { writeText("{not-json") }
            val missing = File(tempRoot, "missing.json")
            val discovery = DesktopObsidianVaultDiscovery(configFiles = { listOf(missing, broken) })
            assertTrue(discovery.findVaults().isEmpty())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `defaultObsidianConfigFiles resolves platform-specific paths`() {
        val winFiles = defaultObsidianConfigFiles(
            osName = "Windows 11",
            userHome = "C:\\Users\\me",
            env = { if (it == "APPDATA") "C:\\Users\\me\\AppData\\Roaming" else null },
        )
        assertEquals(listOf(File("C:\\Users\\me\\AppData\\Roaming", "obsidian/obsidian.json")), winFiles)

        val macFiles = defaultObsidianConfigFiles(
            osName = "Mac OS X",
            userHome = "/Users/me",
            env = { null },
        )
        assertEquals(listOf(File("/Users/me/Library/Application Support/obsidian/obsidian.json")), macFiles)

        val linuxFiles = defaultObsidianConfigFiles(
            osName = "Linux",
            userHome = "/home/me",
            env = { if (it == "XDG_CONFIG_HOME") "/home/me/.xdg" else null },
        )
        assertEquals(
            listOf(
                File("/home/me/.xdg/obsidian/obsidian.json"),
                File("/home/me/.config/obsidian/obsidian.json"),
                File("/home/me/.var/app/md.obsidian.Obsidian/config/obsidian/obsidian.json"),
            ),
            linuxFiles,
        )
    }

    private fun quoteJson(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
