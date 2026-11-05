package dev.mimir.core.fs

import dev.mimir.core.rules.SyncRules

internal const val VAULT_ID_PREFIX = "fs:"

/** Служебные пути, которые не синхронизируются: те же правила, что использует план синхронизации. */
internal class VaultPathFilter(private val rules: SyncRules) {
    fun isExcluded(relativePath: String): Boolean = rules.isExcluded(relativePath)
}
