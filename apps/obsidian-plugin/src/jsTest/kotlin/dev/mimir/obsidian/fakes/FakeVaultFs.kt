package dev.mimir.obsidian.fakes

import dev.mimir.obsidian.platform.FsStat
import dev.mimir.obsidian.platform.VaultFs
import kotlinx.coroutines.CancellationException

class FakeVaultFs : VaultFs {
    val files = mutableMapOf<String, ByteArray>()
    val mtimes = mutableMapOf<String, Long>()
    val folders = mutableSetOf<String>()
    val callLog = mutableListOf<String>()

    var currentTimeMs: Long = 1000L
    var failOnNthAppendBinary: Int? = null
    var failWithCancellation: Boolean = false
    private var appendBinaryCount = 0

    override suspend fun list(dir: String): Pair<List<String>, List<String>> {
        val normalizedDir = dir.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val foundFiles = mutableListOf<String>()
        val foundFolders = mutableSetOf<String>()

        for (path in files.keys) {
            if (normalizedDir.isEmpty()) {
                val slash = path.indexOf('/')
                if (slash == -1) {
                    foundFiles.add(path)
                } else {
                    foundFolders.add(path.substring(0, slash))
                }
            } else if (path.startsWith(normalizedDir)) {
                val sub = path.substring(normalizedDir.length)
                val slash = sub.indexOf('/')
                if (slash == -1) {
                    foundFiles.add(path)
                } else {
                    foundFolders.add(normalizedDir + sub.substring(0, slash))
                }
            }
        }

        for (folder in folders) {
            if (normalizedDir.isEmpty()) {
                val slash = folder.indexOf('/')
                if (slash == -1 && folder.isNotEmpty()) {
                    foundFolders.add(folder)
                }
            } else if (folder.startsWith(normalizedDir) && folder != normalizedDir.removeSuffix("/")) {
                val sub = folder.substring(normalizedDir.length)
                val slash = sub.indexOf('/')
                if (slash == -1) {
                    foundFolders.add(folder)
                }
            }
        }

        return Pair(foundFiles.sorted(), foundFolders.sorted())
    }

    override suspend fun stat(path: String): FsStat? {
        val norm = path.trim('/')
        if (files.containsKey(norm)) {
            val bytes = files[norm]!!
            val mtime = mtimes[norm] ?: currentTimeMs
            return FsStat(type = "file", size = bytes.size.toLong(), mtime = mtime)
        }
        if (folders.contains(norm)) {
            return FsStat(type = "folder", size = 0L, mtime = currentTimeMs)
        }
        return null
    }

    override suspend fun writeBinary(path: String, data: ByteArray) {
        val norm = path.trim('/')
        callLog.add("writeBinary($norm, ${data.size})")
        files[norm] = data.copyOf()
        mtimes[norm] = currentTimeMs
    }

    override suspend fun appendBinary(path: String, data: ByteArray) {
        val norm = path.trim('/')
        appendBinaryCount++
        callLog.add("appendBinary($norm, ${data.size})")

        if (failOnNthAppendBinary == appendBinaryCount) {
            if (failWithCancellation) {
                throw CancellationException("Simulated cancellation during appendBinary")
            } else {
                throw IllegalStateException("Simulated failure during appendBinary")
            }
        }

        val existing = files[norm] ?: ByteArray(0)
        files[norm] = existing + data
        mtimes[norm] = currentTimeMs
    }

    override suspend fun readBinary(path: String): ByteArray {
        val norm = path.trim('/')
        callLog.add("readBinary($norm)")
        return files[norm]?.copyOf() ?: throw NoSuchElementException("File not found: $norm")
    }

    override suspend fun rename(from: String, to: String) {
        val normFrom = from.trim('/')
        val normTo = to.trim('/')
        callLog.add("rename($normFrom, $normTo)")
        val data = files.remove(normFrom) ?: throw NoSuchElementException("File not found: $normFrom")
        val mtime = mtimes.remove(normFrom) ?: currentTimeMs
        files[normTo] = data
        mtimes[normTo] = mtime
    }

    override suspend fun remove(path: String) {
        val norm = path.trim('/')
        callLog.add("remove($norm)")
        files.remove(norm)
        mtimes.remove(norm)
        folders.remove(norm)
    }

    override suspend fun mkdir(path: String) {
        val norm = path.trim('/')
        callLog.add("mkdir($norm)")
        folders.add(norm)
    }

    override suspend fun exists(path: String): Boolean {
        val norm = path.trim('/')
        return files.containsKey(norm) || folders.contains(norm)
    }
}
