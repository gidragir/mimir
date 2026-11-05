package dev.mimir.android.notify

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatusNotifierTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager
    private lateinit var notifier: StatusNotifier

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        StatusNotifier.clearState(context)
        notifier = StatusNotifier(context)
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `when POST_NOTIFICATIONS is not granted show does nothing silently`() {
        shadowOf(context as android.app.Application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val content = NotificationContent(
            channel = Channel.Status,
            title = "Mimir",
            text = "Всё синхронизировано · 12:40",
            destination = Destination.Status,
            severity = 1,
        )

        notifier.show(content)

        val shadow = shadowOf(notificationManager)
        assertNull(shadow.getNotification(StatusNotifier.ID_STATUS))
    }

    @Test
    fun `shows notification on status channel when permission granted`() {
        val content = NotificationContent(
            channel = Channel.Status,
            title = "Mimir",
            text = "Всё синхронизировано · 12:40",
            destination = Destination.Status,
            severity = 1,
        )

        notifier.show(content)

        val shadow = shadowOf(notificationManager)
        val notification = shadow.getNotification(StatusNotifier.ID_STATUS)
        assertNotNull(notification)
        assertEquals("Mimir", shadowOf(notification).contentTitle)
        assertEquals("Всё синхронизировано · 12:40", shadowOf(notification).contentText)
    }

    @Test
    fun `shows notification on attention channel`() {
        val content = NotificationContent(
            channel = Channel.Attention,
            title = "Mimir",
            text = "Конфликт: 2 файла",
            destination = Destination.Conflicts,
            severity = 4,
        )

        notifier.show(content)

        val shadow = shadowOf(notificationManager)
        val notification = shadow.getNotification(StatusNotifier.ID_ATTENTION)
        assertNotNull(notification)
        assertEquals("Mimir", shadowOf(notification).contentTitle)
        assertEquals("Конфликт: 2 файла", shadowOf(notification).contentText)
    }

    @Test
    fun `status notification cancels previous attention notification`() {
        val attention = NotificationContent(
            channel = Channel.Attention,
            title = "Mimir",
            text = "Конфликт: 1 файл",
            destination = Destination.Conflicts,
            severity = 4,
        )
        val status = NotificationContent(
            channel = Channel.Status,
            title = "Mimir",
            text = "Всё синхронизировано · 12:40",
            destination = Destination.Status,
            severity = 1,
        )

        notifier.show(attention)
        val shadow = shadowOf(notificationManager)
        assertNotNull(shadow.getNotification(StatusNotifier.ID_ATTENTION))

        notifier.show(status)
        assertNull(shadow.getNotification(StatusNotifier.ID_ATTENTION))
        assertNotNull(shadow.getNotification(StatusNotifier.ID_STATUS))
    }

    @Test
    fun `attention notification cancels previous status notification`() {
        val status = NotificationContent(
            channel = Channel.Status,
            title = "Mimir",
            text = "Всё синхронизировано · 12:40",
            destination = Destination.Status,
            severity = 1,
        )
        val attention = NotificationContent(
            channel = Channel.Attention,
            title = "Mimir",
            text = "Войдите в Google снова",
            destination = Destination.Login,
            severity = 6,
        )

        notifier.show(status)
        val shadow = shadowOf(notificationManager)
        assertNotNull(shadow.getNotification(StatusNotifier.ID_STATUS))

        notifier.show(attention)
        assertNull(shadow.getNotification(StatusNotifier.ID_STATUS))
        assertNotNull(shadow.getNotification(StatusNotifier.ID_ATTENTION))
    }

    @Test
    fun `dismissed notification is not shown again with same or lower severity`() {
        val status = NotificationContent(
            channel = Channel.Status,
            title = "Mimir",
            text = "Всё синхронизировано · 12:40",
            destination = Destination.Status,
            severity = 1,
        )

        notifier.show(status)
        notificationManager.cancel(StatusNotifier.ID_STATUS)
        StatusNotifier.recordDismissed(context, severity = 1)

        // Повторный показ с той же severity не должен создать уведомление
        notifier.show(status)
        val shadow = shadowOf(notificationManager)
        assertNull(shadow.getNotification(StatusNotifier.ID_STATUS))
    }

    @Test
    fun `dismissed notification is shown again when severity increases`() {
        StatusNotifier.recordDismissed(context, severity = 1)

        val attention = NotificationContent(
            channel = Channel.Attention,
            title = "Mimir",
            text = "Конфликт: 1 файл",
            destination = Destination.Conflicts,
            severity = 4,
        )

        notifier.show(attention)
        val shadow = shadowOf(notificationManager)
        assertNotNull(shadow.getNotification(StatusNotifier.ID_ATTENTION))
    }

    @Test
    fun `pending intent contains extra destination`() {
        val content = NotificationContent(
            channel = Channel.Attention,
            title = "Mimir",
            text = "Войдите в Google снова",
            destination = Destination.Login,
            severity = 6,
        )

        notifier.show(content)

        val shadow = shadowOf(notificationManager)
        val notification = shadow.getNotification(StatusNotifier.ID_ATTENTION)
        assertNotNull(notification)
        assertNotNull(notification.contentIntent)

        val shadowIntent = shadowOf(notification.contentIntent)
        val intent = shadowIntent.savedIntent
        assertEquals("login", intent.getStringExtra(StatusNotifier.EXTRA_DESTINATION))
    }
}
