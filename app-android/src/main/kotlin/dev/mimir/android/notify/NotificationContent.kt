package dev.mimir.android.notify

import dev.mimir.core.SyncErrorKind
import dev.mimir.core.SyncStatus
import dev.mimir.ui.screens.SyncTexts
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * Каналы системных уведомлений Mimir.
 *
 * [Status] — id "mimir-status" (IMPORTANCE_LOW, без звука), статус текущего состояния синхронизации.
 * [Attention] — id "mimir-attention" (IMPORTANCE_DEFAULT), требует внимания пользователя.
 */
enum class Channel(val id: String, val importance: Int, val channelName: String) {
    Status("mimir-status", 2 /* NotificationManager.IMPORTANCE_LOW */, "Статус"),
    Attention("mimir-attention", 3 /* NotificationManager.IMPORTANCE_DEFAULT */, "Требует внимания"),
}

/**
 * Точка назначения при нажатии на уведомление.
 * Передаётся в extra "destination" для [MainActivity].
 */
typealias Destination = dev.mimir.ui.Destination


/**
 * Содержимое системного уведомления Mimir.
 */
data class NotificationContent(
    val channel: Channel,
    val title: String,
    val text: String,
    val destination: Destination,
    val severity: Int,
)

/**
 * Формирует содержимое уведомления по текущему состоянию синхронизации.
 * Возвращает `null`, если показывать уведомление не нужно (например, при ненастроенном vault или во время синхронизации).
 */
fun notificationFor(
    status: SyncStatus,
    conflicts: Int,
    pendingLocal: Boolean,
    lastSync: Instant?,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): NotificationContent? {
    if (status is SyncStatus.NotConfigured) {
        return null
    }

    if (status is SyncStatus.Error) {
        return when (status.kind) {
            SyncErrorKind.SignInRequired -> NotificationContent(
                channel = Channel.Attention,
                title = "Mimir",
                text = SyncTexts.SIGN_IN_REQUIRED,
                destination = Destination.Login,
                severity = 6,
            )
            SyncErrorKind.Aborted -> NotificationContent(
                channel = Channel.Attention,
                title = "Mimir",
                text = SyncTexts.SYNC_ABORTED_EMPTY_DRIVE,
                destination = Destination.Status,
                severity = 5,
            )
            SyncErrorKind.Other -> NotificationContent(
                channel = Channel.Attention,
                title = "Mimir",
                text = SyncTexts.FAILED,
                destination = Destination.Status,
                severity = 5,
            )
        }
    }

    if (conflicts > 0) {
        return NotificationContent(
            channel = Channel.Attention,
            title = "Mimir",
            text = SyncTexts.notificationConflict(conflicts),
            destination = Destination.Conflicts,
            severity = 4,
        )
    }

    if (status is SyncStatus.Offline) {
        val text = if (pendingLocal) {
            SyncTexts.OFFLINE_PENDING
        } else {
            SyncTexts.OFFLINE
        }
        return NotificationContent(
            channel = Channel.Status,
            title = "Mimir",
            text = text,
            destination = Destination.Status,
            severity = if (pendingLocal) 3 else 2,
        )
    }

    if (status is SyncStatus.Idle) {
        if (pendingLocal) {
            return NotificationContent(
                channel = Channel.Status,
                title = "Mimir",
                text = SyncTexts.PENDING_CHANGES,
                destination = Destination.Status,
                severity = 2,
            )
        }
        val text = if (lastSync != null) {
            SyncTexts.notificationSynced(lastSync, timeZone)
        } else {
            SyncTexts.NOTIFICATION_SYNCED
        }
        return NotificationContent(
            channel = Channel.Status,
            title = "Mimir",
            text = text,
            destination = Destination.Status,
            severity = 1,
        )
    }

    return null
}
