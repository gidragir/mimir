package dev.mimir.ui.screens

import dev.mimir.core.SyncItem
import dev.mimir.core.SyncItemAction
import dev.mimir.core.SyncPhase
import dev.mimir.core.SyncProblemKind
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Все тексты экрана синхронизации — для человека, без технических терминов. */
object SyncTexts {
    const val SCANNING = "Ищу изменения…"
    const val SYNCING = "Синхронизация…"
    const val DONE = "✓ Всё синхронизировано"
    const val OFFLINE = "Нет подключения к интернету"
    const val SIGN_IN_AGAIN = "Нужно снова войти в Google"
    const val FAILED = "Не удалось синхронизировать"
    const val OFFLINE_HINT = "Mimir продолжит, когда сеть появится. Изменения на этом компьютере сохранены."
    const val RETRY_HINT = "Mimir попробует ещё раз при следующей синхронизации"
    const val DETAILS = "Подробнее"
    const val NOW = "Сейчас"
    const val NEXT = "Далее"
    const val SYNC_NOW = "Синхронизировать сейчас"
    const val SIGN_IN = "Войти через Google"

    /**
     * Заголовок фазы. `null` — показывать служебную строку статуса (до подключения: ровно `Mimir: not connected`).
     * [needsSignIn] — ошибка вызвана тем, что вход в Google больше не действует.
     */
    fun phaseTitle(status: SyncStatus, progress: SyncProgress?, needsSignIn: Boolean): String? = when (status) {
        SyncStatus.NotConfigured -> null
        SyncStatus.Offline -> OFFLINE
        is SyncStatus.Error -> if (needsSignIn) SIGN_IN_AGAIN else FAILED
        SyncStatus.Idle -> DONE
        SyncStatus.Syncing -> when (progress?.phase) {
            SyncPhase.Scanning, SyncPhase.Comparing -> SCANNING
            SyncPhase.Finished -> DONE
            SyncPhase.Applying, null -> SYNCING
        }
    }

    fun actionNow(item: SyncItem): String = when (item.action) {
        SyncItemAction.Upload -> "⬆ Отправляю в Google Drive: ${item.path}"
        SyncItemAction.Download -> "⬇ Загружаю: ${item.path}"
        SyncItemAction.DeleteLocal, SyncItemAction.DeleteRemote -> "Удаляю: ${item.path}"
        SyncItemAction.Conflict -> "Сохраняю обе версии: ${item.path}"
    }

    fun problemHint(kind: SyncProblemKind): String = when (kind) {
        SyncProblemKind.ReadFailed -> "не удалось прочитать файл — возможно, он открыт в другой программе"
        SyncProblemKind.WriteFailed -> "не удалось сохранить файл — проверьте свободное место и права на папку"
        SyncProblemKind.DecryptFailed -> "не удалось расшифровать — возможно, файл зашифрован другим паролем"
        SyncProblemKind.Unknown -> "непредвиденная ошибка"
    }

    fun problemsTitle(count: Int): String = "⚠ ${files(count)} не удалось синхронизировать"

    /** «1 файл», «2 файла», «5 файлов». */
    fun files(n: Int): String = "$n " + plural(n, "файл", "файла", "файлов")

    /** «12 из 87 файлов», «1 из 21 файла» (после «из» — родительный падеж). */
    fun counter(done: Int, total: Int): String =
        "$done из $total " + plural(total, "файла", "файлов", "файлов")

    fun scanned(n: Int): String = "Проверено ${files(n)}"

    fun elapsed(seconds: Long): String = "прошло ${duration(seconds)}"

    fun remaining(seconds: Long): String = "осталось ${duration(seconds, approximate = true)}"

    fun upcomingTitle(more: Int): String = if (more > 0) "$NEXT · и ещё $more" else NEXT

    fun completedTitle(count: Int, expanded: Boolean): String = "Готово ($count) " + if (expanded) "▾" else "▸"

    fun finishedSummary(done: Int, seconds: Long): String =
        if (done > 0) "${files(done)} за ${duration(seconds)}" else "Изменений не было"

    /** «сегодня в 14:32», «вчера в 09:05», «24.10 в 14:32». */
    fun whenFinished(at: Instant, now: Instant, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val local = at.toLocalDateTime(zone)
        val today = now.toLocalDateTime(zone).date
        val time = "${local.hour.pad()}:${local.minute.pad()}"
        val day = when (today.toEpochDays() - local.date.toEpochDays()) {
            0L -> "сегодня"
            1L -> "вчера"
            else -> "${local.day.pad()}.${local.month.ordinal.plus(1).pad()}"
        }
        return "$day в $time"
    }

    /** Точная длительность: «12 с», «1 мин 20 с», «4 мин 05 с», «1 ч 02 мин»; приблизительная: «≈ 40 с», «≈ 3 мин». */
    fun duration(seconds: Long, approximate: Boolean = false): String {
        val s = seconds.coerceAtLeast(0)
        if (approximate) {
            if (s < 60) return "≈ $s с"
            val minutes = (s + 30) / 60
            return if (minutes < 60) "≈ $minutes мин" else "≈ ${minutes / 60} ч ${(minutes % 60).toInt().pad()} мин"
        }
        return when {
            s < 60 -> "$s с"
            s < 3600 -> "${s / 60} мин ${(s % 60).toInt().pad()} с"
            else -> "${s / 3600} ч ${((s % 3600) / 60).toInt().pad()} мин"
        }
    }

    fun duration(seconds: Int, approximate: Boolean = false): String = duration(seconds.toLong(), approximate)

    private fun plural(n: Int, one: String, few: String, many: String): String {
        val mod100 = n % 100
        val mod10 = n % 10
        return when {
            mod100 in 11..14 -> many
            mod10 == 1 -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }

    private fun Int.pad(): String = toString().padStart(2, '0')
}
