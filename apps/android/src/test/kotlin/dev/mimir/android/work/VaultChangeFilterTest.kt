package dev.mimir.android.work

import dev.mimir.core.rules.SyncRules
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaultChangeFilterTest {
    private val vaultRoot = "/storage/emulated/0/Vault"
    private val defaultRules = SyncRules()

    @Test
    fun `path inside vault is relevant`() {
        val paths = listOf("/storage/emulated/0/Vault/notes/daily.md")
        assertTrue(VaultChangeFilter.relevant(paths, vaultRoot, defaultRules, emptySet()))
    }

    @Test
    fun `path outside vault is ignored`() {
        val paths = listOf("/storage/emulated/0/Download/report.pdf")
        assertFalse(VaultChangeFilter.relevant(paths, vaultRoot, defaultRules, emptySet()))
    }

    @Test
    fun `excluded path is ignored`() {
        val paths = listOf("/storage/emulated/0/Vault/.obsidian/workspace.json")
        assertFalse(VaultChangeFilter.relevant(paths, vaultRoot, defaultRules, emptySet()))
    }

    @Test
    fun `paths written by sync are ignored`() {
        val paths = listOf("/storage/emulated/0/Vault/notes/synced.md")
        val writtenBySync = setOf("notes/synced.md")
        assertFalse(VaultChangeFilter.relevant(paths, vaultRoot, defaultRules, writtenBySync))
    }

    @Test
    fun `vault root prefix does not match sibling folder`() {
        val paths = listOf("/storage/emulated/0/Vault2/a.md")
        assertFalse(VaultChangeFilter.relevant(paths, "/storage/emulated/0/Vault", defaultRules, emptySet()))
    }

    @Test
    fun `at least one relevant path makes change relevant`() {
        val paths = listOf(
            "/storage/emulated/0/Download/other.txt",
            "/storage/emulated/0/Vault/.obsidian/workspace.json",
            "/storage/emulated/0/Vault/notes/valid.md",
        )
        assertTrue(VaultChangeFilter.relevant(paths, vaultRoot, defaultRules, emptySet()))
    }

    @Test
    fun `empty paths list is not relevant`() {
        assertFalse(VaultChangeFilter.relevant(emptyList(), vaultRoot, defaultRules, emptySet()))
    }
}
