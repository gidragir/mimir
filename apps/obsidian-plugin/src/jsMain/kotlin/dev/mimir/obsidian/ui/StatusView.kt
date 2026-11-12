package dev.mimir.obsidian.ui

import dev.mimir.obsidian.sync.PluginState
import kotlin.time.Duration
import kotlin.time.Instant

fun statusLabel(state: PluginState, texts: PluginTexts, now: Instant): String {
    return when (state) {
        is PluginState.NotConnected -> texts.statusNotConnected
        is PluginState.Offline -> texts.statusOffline
        is PluginState.YieldsToDaemon -> texts.statusYieldsToDaemon
        is PluginState.PausedForDaemon -> texts.statusPausedForDaemon
        is PluginState.NeedsSignIn -> texts.statusNeedsSignIn
        is PluginState.Error -> texts.statusError
        is PluginState.Syncing -> texts.statusSyncing(state.done, state.total)
        is PluginState.UpToDate -> {
            if (state.conflicts.isNotEmpty()) {
                texts.statusConflicts(state.conflicts.size)
            } else {
                "Mimir: ${texts.statusUpToDate} · ${formatRelativeTime(state.at, now, texts)}"
            }
        }
    }
}

fun formatRelativeTime(at: Instant, now: Instant, texts: PluginTexts): String {
    val deltaSeconds = (now - at).inWholeSeconds
    return when {
        deltaSeconds < 60 -> texts.relativeJustNow
        deltaSeconds < 3600 -> texts.relativeMinAgo(deltaSeconds / 60)
        deltaSeconds < 86400 -> texts.relativeHrAgo(deltaSeconds / 3600)
        else -> texts.relativeDaysAgo(deltaSeconds / 86400)
    }
}
