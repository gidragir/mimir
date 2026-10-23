package dev.mimir.core

import kotlinx.datetime.LocalTime

data class TimeWindow(val start: LocalTime, val end: LocalTime)

/** Только модель правил, без логики применения. */
data class SyncRules(
    val wifiOnly: Boolean = false,
    val windows: List<TimeWindow> = emptyList(),
    val excludedGlobs: List<String> = listOf(".obsidian/workspace*.json"),
)
