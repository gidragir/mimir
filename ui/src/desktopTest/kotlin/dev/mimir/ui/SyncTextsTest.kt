package dev.mimir.ui

import dev.mimir.ui.screens.SyncTexts
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
}
