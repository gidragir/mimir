package dev.mimir.core

import dev.mimir.core.rules.GlobMatcher
import dev.mimir.core.rules.SyncRules
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlobMatcherTest {

    @Test
    fun exactMatches() {
        assertTrue(GlobMatcher.matches("note.md", "note.md"))
        assertTrue(GlobMatcher.matches("folder/note.md", "folder/note.md"))
        assertFalse(GlobMatcher.matches("note.md", "other.md"))
        assertFalse(GlobMatcher.matches("folder/note.md", "folder/other.md"))
    }

    @Test
    fun singleStarMatchesWithinSegment() {
        assertTrue(GlobMatcher.matches("*.md", "note.md"))
        assertTrue(GlobMatcher.matches("*.md", "a.md"))
        assertFalse(GlobMatcher.matches("*.md", "sub/note.md"))

        assertTrue(GlobMatcher.matches(".obsidian/workspace*.json", ".obsidian/workspace.json"))
        assertTrue(GlobMatcher.matches(".obsidian/workspace*.json", ".obsidian/workspace-mobile.json"))
        assertFalse(GlobMatcher.matches(".obsidian/workspace*.json", ".obsidian/nested/workspace.json"))
    }

    @Test
    fun doubleStarMatchesAcrossSegments() {
        assertTrue(GlobMatcher.matches(".obsidian/cache/**", ".obsidian/cache/file.bin"))
        assertTrue(GlobMatcher.matches(".obsidian/cache/**", ".obsidian/cache/sub/file.bin"))
        assertFalse(GlobMatcher.matches(".obsidian/cache/**", ".obsidian/other/file.bin"))

        assertTrue(GlobMatcher.matches("**/.DS_Store", ".DS_Store"))
        assertTrue(GlobMatcher.matches("**/.DS_Store", "folder/.DS_Store"))
        assertTrue(GlobMatcher.matches("**/.DS_Store", "a/b/c/.DS_Store"))
        assertFalse(GlobMatcher.matches("**/.DS_Store", "folder/.DS_Store2"))

        assertTrue(GlobMatcher.matches(".trash/**", ".trash/note.md"))
        assertTrue(GlobMatcher.matches(".trash/**", ".trash/folder/note.md"))
        assertFalse(GlobMatcher.matches(".trash/**", "notes/.trash/note.md"))
    }

    @Test
    fun questionMarkMatchesSingleChar() {
        assertTrue(GlobMatcher.matches("note?.md", "note1.md"))
        assertTrue(GlobMatcher.matches("note?.md", "noteA.md"))
        assertFalse(GlobMatcher.matches("note?.md", "note.md"))
        assertFalse(GlobMatcher.matches("note?.md", "note12.md"))
        assertFalse(GlobMatcher.matches("note?.md", "note/.md"))
    }

    @Test
    fun pathNormalization() {
        assertTrue(GlobMatcher.matches("folder/*.md", "/folder/note.md"))
        assertTrue(GlobMatcher.matches("/folder/*.md", "folder/note.md"))
        assertTrue(GlobMatcher.matches("folder/*.md", "folder\\note.md"))
    }

    @Test
    fun syncRulesIsExcluded() {
        val rules = SyncRules(
            excludedGlobs = listOf(
                ".obsidian/workspace*.json",
                ".obsidian/cache/**",
                ".trash/**",
                "**/.DS_Store",
            )
        )

        assertTrue(rules.isExcluded(".obsidian/workspace.json"))
        assertTrue(rules.isExcluded(".obsidian/workspace-mobile.json"))
        assertTrue(rules.isExcluded(".obsidian/cache/indexed.db"))
        assertTrue(rules.isExcluded(".trash/deleted.md"))
        assertTrue(rules.isExcluded("subfolder/.DS_Store"))

        assertFalse(rules.isExcluded(".obsidian/app.json"))
        assertFalse(rules.isExcluded("Daily/2026-10-23.md"))
        assertFalse(rules.isExcluded("Work/Projects.md"))
    }
}
