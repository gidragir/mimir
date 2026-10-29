package dev.mimir.android.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.mimir.android.MainActivity
import dev.mimir.android.R

/**
 * Приёмник событий смахивания (dismiss) уведомлений для фиксации [NotificationContent.severity].
 */
class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val severity = intent.getIntExtra(StatusNotifier.EXTRA_SEVERITY, 0)
        StatusNotifier.recordDismissed(context, severity)
    }
}

/**
 * Отвечает за показ и управление системными уведомлениями Mimir на Android.
 *
 * - id 1: канал [Channel.Status] (низкая важность, без звука)
 * - id 2: канал [Channel.Attention] (обычная важность)
 * - Нажатие открывает [MainActivity] с extra "destination"
 * - Смахнутое уведомление показывается снова только при большем [NotificationContent.severity]
 * - Если POST_NOTIFICATIONS не выдан — молча ничего не делает
 */
class StatusNotifier(context: Context) {

    private val context: Context = context.applicationContext
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannels()
    }

    /**
     * Показывает уведомление с заданным [content].
     * Если разрешение не выдано или уведомление с этим/большим severity было смахнуто, вызов игнорируется.
     */
    fun show(content: NotificationContent) {
        if (!areNotificationsAllowed()) {
            return
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSeverity = prefs.getInt(KEY_LAST_SEVERITY, 0)
        var dismissedSeverity = prefs.getInt(KEY_DISMISSED_SEVERITY, 0)

        // При улучшении статуса (уменьшении severity) сбрасываем состояние смахнутого уведомления
        if (content.severity < lastSeverity) {
            prefs.edit().putInt(KEY_DISMISSED_SEVERITY, 0).apply()
            dismissedSeverity = 0
        }

        // Смахнутое уведомление показывается снова только при большем severity
        if (content.severity <= dismissedSeverity) {
            return
        }

        val notificationId = when (content.channel) {
            Channel.Status -> ID_STATUS
            Channel.Attention -> ID_ATTENTION
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_DESTINATION, content.destination.name.lowercase())
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val deleteIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            Intent(context, NotificationDismissReceiver::class.java).apply {
                action = ACTION_DISMISSED
                putExtra(EXTRA_SEVERITY, content.severity)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, content.channel.id)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setContentIntent(contentIntent)
            .setDeleteIntent(deleteIntent)
            .setAutoCancel(content.channel == Channel.Attention)
            .setOngoing(false)
            .build()

        when (content.channel) {
            Channel.Status -> {
                notificationManager.notify(ID_STATUS, notification)
                notificationManager.cancel(ID_ATTENTION)
            }
            Channel.Attention -> {
                notificationManager.notify(ID_ATTENTION, notification)
                notificationManager.cancel(ID_STATUS)
            }
        }

        prefs.edit().putInt(KEY_LAST_SEVERITY, content.severity).apply()
    }

    /**
     * Отменяет все активные уведомления Mimir.
     */
    fun cancelAll() {
        notificationManager.cancel(ID_STATUS)
        notificationManager.cancel(ID_ATTENTION)
    }

    private fun areNotificationsAllowed(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val statusChannel = NotificationChannel(
                Channel.Status.id,
                Channel.Status.channelName,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
            }
            val attentionChannel = NotificationChannel(
                Channel.Attention.id,
                Channel.Attention.channelName,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                setShowBadge(true)
            }
            notificationManager.createNotificationChannels(listOf(statusChannel, attentionChannel))
        }
    }

    companion object {
        const val ID_STATUS = 1
        const val ID_ATTENTION = 2
        const val EXTRA_DESTINATION = "destination"
        const val EXTRA_SEVERITY = "severity"
        const val ACTION_DISMISSED = "dev.mimir.android.notify.ACTION_DISMISSED"

        private const val PREFS_NAME = "mimir_notifications"
        private const val KEY_DISMISSED_SEVERITY = "dismissed_severity"
        private const val KEY_LAST_SEVERITY = "last_severity"

        fun recordDismissed(context: Context, severity: Int) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_DISMISSED_SEVERITY, severity)
                .apply()
        }

        fun clearState(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
        }
    }
}
