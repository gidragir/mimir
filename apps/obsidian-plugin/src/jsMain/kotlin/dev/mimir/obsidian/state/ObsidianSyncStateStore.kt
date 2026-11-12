package dev.mimir.obsidian.state

import dev.mimir.core.sync.SyncIndex
import dev.mimir.core.sync.SyncIndexJson
import dev.mimir.core.sync.SyncStateStore
import dev.mimir.obsidian.platform.VaultFs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Хранилище индекса синхронизации плагина Obsidian.
 * Хранит [SyncIndex] в файле `<configDir>/plugins/<PLUGIN_ID>/index.json`.
 * Запись атомарна через временный файл `index.json.tmp`.
 * При повреждении JSON файл переименовывается в `index.json.corrupt`, а вызывающей стороне
 * возвращается пустой [SyncIndex.empty].
 */
class ObsidianSyncStateStore(
    private val fs: VaultFs,
    pluginDir: String,
) : SyncStateStore {
    private val mutex = Mutex()
    private val dir = pluginDir.trim('/')
    private val indexFile = "$dir/index.json"
    private val tmpFile = "$dir/index.json.tmp"
    private val corruptFile = "$dir/index.json.corrupt"

    override suspend fun load(): SyncIndex = mutex.withLock {
        if (!fs.exists(indexFile)) return SyncIndex()
        val text = fs.readBinary(indexFile).decodeToString()
        try {
            SyncIndexJson.decode(text)
        } catch (_: Throwable) {
            try {
                if (fs.exists(corruptFile)) {
                    fs.remove(corruptFile)
                }
                fs.rename(indexFile, corruptFile)
            } catch (_: Throwable) {
            }
            SyncIndex()
        }
    }

    override suspend fun save(index: SyncIndex): Unit = mutex.withLock {
        if (!fs.exists(dir)) {
            val parts = dir.split('/')
            var current = ""
            for (p in parts) {
                if (p.isEmpty()) continue
                current = if (current.isEmpty()) p else "$current/$p"
                if (!fs.exists(current)) {
                    fs.mkdir(current)
                }
            }
        }

        val jsonText = SyncIndexJson.encode(index)
        val bytes = jsonText.encodeToByteArray()

        fs.writeBinary(tmpFile, bytes)
        if (fs.exists(indexFile)) {
            fs.remove(indexFile)
        }
        fs.rename(tmpFile, indexFile)
    }
}
