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
    fun isExcluded(path: String): Boolean =
        excludedGlobs.any { glob -> GlobMatcher.matches(glob, path) }
}
