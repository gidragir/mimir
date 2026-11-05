package dev.mimir.android.platform

import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileSystemVaultDiscoveryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `finds vault two levels deep`() = runBlocking {
        val root = tempFolder.newFolder("storage")
        val docs = File(root, "Documents").apply { mkdirs() }
        val vault = File(docs, "Vault").apply { mkdirs() }
        File(vault, ".obsidian").apply { mkdirs() }

        val discovery = FileSystemVaultDiscovery(roots = listOf(root), maxDepth = 2)
        val vaults = discovery.findVaults()

        assertEquals(listOf(vault.canonicalPath), vaults)
    }

    @Test
    fun `ignores deeper vaults`() = runBlocking {
        val root = tempFolder.newFolder("storage")
        val docs = File(root, "Documents").apply { mkdirs() }
        val sub = File(docs, "Sub").apply { mkdirs() }
        val deepVault = File(sub, "DeepVault").apply { mkdirs() }
        File(deepVault, ".obsidian").apply { mkdirs() }

        val discovery = FileSystemVaultDiscovery(roots = listOf(root), maxDepth = 2)
        val vaults = discovery.findVaults()

        assertTrue(vaults.isEmpty())
    }

    @Test
    fun `ignores folders without obsidian dir`() = runBlocking {
        val root = tempFolder.newFolder("storage")
        val docs = File(root, "Documents").apply { mkdirs() }
        val normalFolder = File(docs, "RegularFolder").apply { mkdirs() }
        File(normalFolder, "note.md").writeText("Hello")

        val discovery = FileSystemVaultDiscovery(roots = listOf(root), maxDepth = 2)
        val vaults = discovery.findVaults()

        assertTrue(vaults.isEmpty())
    }
}
