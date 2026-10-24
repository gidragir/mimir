package dev.mimir.core.rules

import kotlinx.datetime.LocalTime

data class TimeWindow(val start: LocalTime, val end: LocalTime)

data class SyncRules(
    val wifiOnly: Boolean = false,
    val windows: List<TimeWindow> = emptyList(),
    val excludedGlobs: List<String> = listOf(
        ".obsidian/workspace*.json",
        ".obsidian/cache/**",
        ".trash/**",
        "**/.DS_Store",
    ),
) {
    /** Служебные пути отсекаются всегда (и локально, и в плане по удалённому списку и индексу), правила пользователя — сверх них. */
    fun isExcluded(path: String): Boolean =
        BUILTIN_EXCLUDES.any { GlobMatcher.matches(it, path) } || excludedGlobs.any { glob -> GlobMatcher.matches(glob, path) }

    private companion object {
        val BUILTIN_EXCLUDES = listOf(".git/**", "**/.git/**", "**/node_modules/**", "**/*.tmp", "**/*.crswap", "**/.mimir*")
    }
}
