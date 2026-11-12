package dev.mimir.obsidian.ui

import dev.mimir.obsidian.sync.PluginState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class StatusViewTest {

    private val now = Instant.fromEpochMilliseconds(1_000_000_000_000L)

    @Test
    fun notConnectedIsExactLabel() {
        val en = PluginTexts.forLocale("en")
        val ru = PluginTexts.forLocale("ru")

        assertEquals("Mimir: not connected", statusLabel(PluginState.NotConnected, en, now))
        assertEquals("Mimir: not connected", statusLabel(PluginState.NotConnected, ru, now))
    }

    @Test
    fun statusLabelForStates() {
        val en = PluginTexts.forLocale("en")
        val ru = PluginTexts.forLocale("ru")

        assertEquals("Mimir: offline", statusLabel(PluginState.Offline, en, now))
        assertEquals("Mimir: offline", statusLabel(PluginState.Offline, ru, now))

        assertEquals("Mimir: handled by Mimir Desktop", statusLabel(PluginState.YieldsToDaemon, en, now))
        assertEquals("Mimir: handled by Mimir Desktop", statusLabel(PluginState.YieldsToDaemon, ru, now))

        assertEquals("Mimir: paused — Mimir Desktop is running", statusLabel(PluginState.PausedForDaemon, en, now))
        assertEquals("Mimir: приостановлено — запущен Mimir Desktop", statusLabel(PluginState.PausedForDaemon, ru, now))

        assertEquals("Mimir: syncing 5/10", statusLabel(PluginState.Syncing(5, 10), en, now))
        assertEquals("Mimir: синхронизация 5/10", statusLabel(PluginState.Syncing(5, 10), ru, now))

        assertEquals("Mimir: 3 conflicts", statusLabel(PluginState.UpToDate(now, emptyList(), listOf("a", "b", "c")), en, now))
        assertEquals("Mimir: 3 конфликта", statusLabel(PluginState.UpToDate(now, emptyList(), listOf("a", "b", "c")), ru, now))
    }

    @Test
    fun relativeTime() {
        val en = PluginTexts.forLocale("en")
        val ru = PluginTexts.forLocale("ru")

        // Just now
        val tJustNow = now - 15.seconds
        assertEquals("Mimir: up to date · just now", statusLabel(PluginState.UpToDate(tJustNow, emptyList(), emptyList()), en, now))
        assertEquals("Mimir: синхронизировано · только что", statusLabel(PluginState.UpToDate(tJustNow, emptyList(), emptyList()), ru, now))

        // Minutes ago
        val t2m = now - 2.minutes
        assertEquals("Mimir: up to date · 2 min ago", statusLabel(PluginState.UpToDate(t2m, emptyList(), emptyList()), en, now))
        assertEquals("Mimir: синхронизировано · 2 мин назад", statusLabel(PluginState.UpToDate(t2m, emptyList(), emptyList()), ru, now))

        // Hours ago
        val t3h = now - 3.hours
        assertEquals("Mimir: up to date · 3 hr ago", statusLabel(PluginState.UpToDate(t3h, emptyList(), emptyList()), en, now))
        assertEquals("Mimir: синхронизировано · 3 ч назад", statusLabel(PluginState.UpToDate(t3h, emptyList(), emptyList()), ru, now))

        // Days ago
        val t4d = now - 4.days
        assertEquals("Mimir: up to date · 4 d ago", statusLabel(PluginState.UpToDate(t4d, emptyList(), emptyList()), en, now))
        assertEquals("Mimir: синхронизировано · 4 дн назад", statusLabel(PluginState.UpToDate(t4d, emptyList(), emptyList()), ru, now))
    }
}
