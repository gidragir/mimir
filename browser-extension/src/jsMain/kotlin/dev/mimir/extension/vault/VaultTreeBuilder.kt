package dev.mimir.extension.vault

import dev.mimir.core.Cipher
import dev.mimir.core.CryptException
import dev.mimir.core.RemoteEntry
import dev.mimir.core.RemotePath
import dev.mimir.core.rules.SyncRules

sealed interface VaultTreeNode {
    val name: String
    val relativePath: String

    data class Folder(
        override val name: String,
        override val relativePath: String,
        val children: List<VaultTreeNode>,
    ) : VaultTreeNode

    data class File(
        override val name: String,
        override val relativePath: String,
        val encryptedPath: RemotePath,
        val size: Long,
        val modifiedEpochSec: Long,
    ) : VaultTreeNode
}

data class DecryptedVaultSnapshot(val tree: List<VaultTreeNode>, val flatNotes: List<VaultTreeNode.File>)

/** Строит дерево заметок из зашифрованного списка файлов; всё расшифрованное живёт только в памяти. */
object VaultTreeBuilder {
    private const val CANARY = ".mimir_canary"
    private val HIDDEN_PREFIXES = listOf(".obsidian/", ".trash/")

    fun build(encryptedEntries: List<RemoteEntry>, cipher: Cipher, rules: SyncRules = SyncRules()): DecryptedVaultSnapshot {
        val root = MutableFolder("", "")
        for (entry in encryptedEntries) {
            if (entry.isDirectory) continue
            val encSegments = entry.path.segments
            if (encSegments.isEmpty() || encSegments.singleOrNull() == CANARY) continue
            // Чужие незашифрованные файлы в хранилище не должны ронять построение всего дерева
            val segments = try {
                encSegments.map(cipher::decryptName)
            } catch (e: CryptException) {
                continue
            }
            val path = segments.joinToString("/")
            if (!path.endsWith(".md", ignoreCase = true)) continue
            if (HIDDEN_PREFIXES.any { path.startsWith(it) } || rules.isExcluded(path)) continue
            var folder = root
            for (i in 0 until segments.size - 1) {
                val folderPath = segments.subList(0, i + 1).joinToString("/")
                folder = folder.folders.getOrPut(segments[i]) { MutableFolder(segments[i], folderPath) }
            }
            folder.files += VaultTreeNode.File(
                name = segments.last(),
                relativePath = path,
                encryptedPath = entry.path,
                size = entry.size,
                modifiedEpochSec = entry.modifiedAt.epochSeconds,
            )
        }
        val tree = root.toNodes()
        return DecryptedVaultSnapshot(tree, tree.flatten())
    }

    fun filterNotes(notes: List<VaultTreeNode.File>, query: String): List<VaultTreeNode.File> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return notes
        return notes.filter { it.relativePath.lowercase().contains(q) }
    }

    /** `[[Папка/Заметка|Алиас]]`, `[[Заметка#Заголовок]]`, `[[Заметка.md]]`: сначала точный путь, затем имя файла. */
    fun resolveWikilink(notes: List<VaultTreeNode.File>, target: String): VaultTreeNode.File? {
        val clean = target.substringBefore('|').substringBefore('#').trim().removeSuffix(".md").removeSuffix(".MD")
        if (clean.isEmpty()) return null
        notes.firstOrNull { it.relativePath.withoutMd().equals(clean, ignoreCase = true) }?.let { return it }
        return notes.firstOrNull { it.name.withoutMd().equals(clean, ignoreCase = true) }
    }

    private fun String.withoutMd() = if (endsWith(".md", ignoreCase = true)) dropLast(3) else this

    private fun List<VaultTreeNode>.flatten(): List<VaultTreeNode.File> = flatMap {
        when (it) {
            is VaultTreeNode.File -> listOf(it)
            is VaultTreeNode.Folder -> it.children.flatten()
        }
    }

    private class MutableFolder(val name: String, val path: String) {
        val folders = linkedMapOf<String, MutableFolder>()
        val files = mutableListOf<VaultTreeNode.File>()

        fun toNodes(): List<VaultTreeNode> =
            folders.values.sortedBy { it.name.lowercase() }.map { VaultTreeNode.Folder(it.name, it.path, it.toNodes()) } +
                files.sortedBy { it.name.lowercase() }
    }
}
