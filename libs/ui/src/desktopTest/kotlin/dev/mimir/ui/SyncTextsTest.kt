package dev.mimir.ui

import dev.mimir.core.SyncErrorKind
import dev.mimir.core.SyncItem
import dev.mimir.core.SyncItemAction
import dev.mimir.core.SyncStatus
import dev.mimir.ui.model.UiBackgroundSync
import dev.mimir.ui.screens.SyncTexts
import kotlinx.datetime.TimeZone
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncTextsTest {
    @Test
    fun durationFormatsExactAndApproximate() {
        assertEquals("12 с", SyncTexts.duration(12))
        assertEquals("1 мин 20 с", SyncTexts.duration(80))
        assertEquals("4 мин 05 с", SyncTexts.duration(245))
        assertEquals("1 ч 02 мин", SyncTexts.duration(3720))
        assertEquals("≈ 3 мин", SyncTexts.duration(180, approximate = true))
        assertEquals("≈ 1 мин", SyncTexts.duration(70, approximate = true))
        assertEquals("≈ 40 с", SyncTexts.duration(40, approximate = true))
    }

    @Test
    fun filesPluralIsRussian() {
        assertEquals("1 файл", SyncTexts.files(1))
        assertEquals("2 файла", SyncTexts.files(2))
        assertEquals("5 файлов", SyncTexts.files(5))
        assertEquals("11 файлов", SyncTexts.files(11))
        assertEquals("21 файл", SyncTexts.files(21))
        assertEquals("12 из 87 файлов", SyncTexts.counter(12, 87))
        assertEquals("1 из 21 файла", SyncTexts.counter(1, 21))
    }

    @Test
    fun scannedWithoutFoldersKeepsOldText() {
        assertEquals("Проверено 340 файлов", SyncTexts.scanned(340))
        assertEquals("Проверено 340 файлов", SyncTexts.scanned(340, 0))
    }

    @Test
    fun scannedWithFoldersUsesPrepositionalPlural() {
        assertEquals("Проверено 340 файлов в 120 папках", SyncTexts.scanned(340, 120))
        assertEquals("Проверено 1 файл в 1 папке", SyncTexts.scanned(1, 1))
        assertEquals("Проверено 0 файлов в 2 папках", SyncTexts.scanned(0, 2))
        assertEquals("Проверено 3 файла в 5 папках", SyncTexts.scanned(3, 5))
        assertEquals("Проверено 3 файла в 11 папках", SyncTexts.scanned(3, 11))
        assertEquals("Проверено 3 файла в 21 папке", SyncTexts.scanned(3, 21))
    }

    @Test
    fun verifyActionTellsThatContentIsComparedWithDrive() {
        assertEquals(
            "Сверяю с Google Drive: notes/a.md",
            SyncTexts.actionNow(SyncItem("notes/a.md", SyncItemAction.Verify)),
        )
    }

    @Test
    fun errorTitleTranslatesErrorKind() {
        assertEquals("Войдите в Google снова", SyncTexts.errorTitle(SyncErrorKind.SignInRequired))
        assertEquals("Синхронизация остановлена: папка в Drive пуста", SyncTexts.errorTitle(SyncErrorKind.Aborted))
        assertEquals("Не удалось синхронизировать", SyncTexts.errorTitle(SyncErrorKind.Other))
    }

    @Test
    fun phaseTitleTranslatesErrorKind() {
        assertEquals(
            "Войдите в Google снова",
            SyncTexts.phaseTitle(SyncStatus.Error("auth", SyncErrorKind.SignInRequired), null, needsSignIn = false),
        )
        assertEquals(
            "Синхронизация остановлена: папка в Drive пуста",
            SyncTexts.phaseTitle(SyncStatus.Error("empty", SyncErrorKind.Aborted), null, needsSignIn = false),
        )
    }

    @Test
    fun backgroundSyncTextFormatsAllStates() {
        assertEquals(
            "Фоновая синхронизация: ждёт Wi‑Fi",
            SyncTexts.backgroundSyncText(UiBackgroundSync(enabled = true, lastRun = null, waitingForWifi = true)),
        )
        assertEquals(
            "Фоновая синхронизация: отключена",
            SyncTexts.backgroundSyncText(UiBackgroundSync(enabled = false, lastRun = null, waitingForWifi = false)),
        )
        assertEquals(
            "Фоновая синхронизация: включена",
            SyncTexts.backgroundSyncText(UiBackgroundSync(enabled = true, lastRun = null, waitingForWifi = false)),
        )
        val time = Instant.parse("2026-10-29T14:32:00Z")
        assertEquals(
            "Фоновая синхронизация: включена · последняя 14:32",
            SyncTexts.backgroundSyncText(
                UiBackgroundSync(enabled = true, lastRun = time, waitingForWifi = false),
                timeZone = TimeZone.UTC,
            ),
        )
    }
}
