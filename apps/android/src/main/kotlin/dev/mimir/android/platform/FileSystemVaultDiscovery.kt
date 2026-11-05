package dev.mimir.android.platform

import dev.mimir.ui.platform.VaultDiscovery
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FileSystemVaultDiscovery(
    private val roots: List<File>,
    private val maxDepth: Int = 2,
) : VaultDiscovery {

    override suspend fun findVaults(): List<String> = withContext(Dispatchers.IO) {
        val found = mutableListOf<String>()
        for (root in roots) {
            scan(root, currentDepth = 0, found)
        }
        found.distinct().sorted()
    }

    private fun scan(dir: File, currentDepth: Int, out: MutableList<String>) {
        if (!dir.exists() || !dir.isDirectory) return

        val obsidianMarker = File(dir, ".obsidian")
        if (obsidianMarker.exists() && obsidianMarker.isDirectory) {
            out.add(dir.canonicalPath)
            return
        }

        if (currentDepth >= maxDepth) return

        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory && !child.name.startsWith(".")) {
                scan(child, currentDepth + 1, out)
            }
        }
    }
}
