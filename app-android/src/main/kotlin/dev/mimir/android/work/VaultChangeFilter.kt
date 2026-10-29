package dev.mimir.android.work

import dev.mimir.core.rules.SyncRules

/**
 * Фильтрует пути из изменений MediaStore, проверяя принадлежность к vault,
 * исключения [SyncRules] и защиту от эха (пути, недавно записанные синхронизацией).
 */
object VaultChangeFilter {
    fun relevant(
        absolutePaths: List<String>,
        vaultRoot: String,
        rules: SyncRules,
        writtenBySync: Set<String>,
    ): Boolean {
        if (absolutePaths.isEmpty()) return false

        val normalizedRoot = vaultRoot.replace('\\', '/').trimEnd('/')
        if (normalizedRoot.isEmpty()) return false

        for (rawPath in absolutePaths) {
            val path = rawPath.replace('\\', '/').trimEnd('/')
            if (!path.startsWith("$normalizedRoot/")) {
                continue
            }
            val rel = path.substring(normalizedRoot.length + 1).trimStart('/')
            if (rel.isEmpty()) {
                continue
            }
            if (rules.isExcluded(rel)) {
                continue
            }
            if (rel in writtenBySync || rawPath in writtenBySync || path in writtenBySync) {
                continue
            }
            return true
        }
        return false
    }
}
