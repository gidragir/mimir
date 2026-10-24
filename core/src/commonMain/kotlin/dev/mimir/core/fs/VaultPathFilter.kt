package dev.mimir.core.fs

import dev.mimir.core.rules.GlobMatcher
import dev.mimir.core.rules.SyncRules

internal const val VAULT_ID_PREFIX = "fs:"

/** Служебные пути, которые не синхронизируются: правила пользователя плюс то, что создаёт сам Mimir/git. */
internal class VaultPathFilter(private val rules: SyncRules) {
    fun isExcluded(relativePath: String): Boolean =
        BUILTIN_EXCLUDES.any { GlobMatcher.matches(it, relativePath) } || rules.isExcluded(relativePath)

    private companion object {
        val BUILTIN_EXCLUDES = listOf(".git/**", "**/*.tmp", "**/*.crswap", "**/.mimir*")
    }
}
