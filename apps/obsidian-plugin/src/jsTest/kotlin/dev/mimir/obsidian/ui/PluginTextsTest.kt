package dev.mimir.obsidian.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginTextsTest {

    @Test
    fun enAndRuTexts() {
        val en = PluginTexts.forLocale("en")
        val ru = PluginTexts.forLocale("ru")

        assertEquals("Mimir: not connected", en.statusNotConnected)
        assertEquals("Mimir: not connected", ru.statusNotConnected) // Инвариант Ruling 2

        assertEquals("Mimir: offline", en.statusOffline)
        assertEquals("Mimir: offline", ru.statusOffline) // Инвариант Ruling 2

        assertEquals("Mimir: handled by Mimir Desktop", en.statusYieldsToDaemon)
        assertEquals("Mimir: handled by Mimir Desktop", ru.statusYieldsToDaemon)

        assertEquals("Mimir: paused — Mimir Desktop is running", en.statusPausedForDaemon)
        assertEquals("Mimir: приостановлено — запущен Mimir Desktop", ru.statusPausedForDaemon)

        assertTrue(en.abortedNothingDeleted.contains("Drive is empty"))
        assertTrue(ru.abortedNothingDeleted.contains("Drive пуст"))
    }
}
