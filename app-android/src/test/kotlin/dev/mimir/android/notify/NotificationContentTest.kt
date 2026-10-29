package dev.mimir.android.notify

import dev.mimir.core.SyncErrorKind
import dev.mimir.core.SyncStatus
import kotlinx.datetime.TimeZone
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class NotificationContentTest {

    @Test
    fun `idle maps to status channel with time`() {
        val lastSync = Instant.parse("2026-10-27T12:40:00Z")
        val content = notificationFor(
            status = SyncStatus.Idle,
            conflicts = 0,
            pendingLocal = false,
            lastSync = lastSync,
            timeZone = TimeZone.UTC,
        )
        assertNotNull(content)
        assertEquals(Channel.Status, content.channel)
        assertEquals(Destination.Status, content.destination)
        assertEquals("Всё синхронизировано · 12:40", content.text)
        assertEquals(1, content.severity)
    }

    @Test
    fun `conflicts map to attention channel with plural form`() {
        val c1 = notificationFor(SyncStatus.Idle, conflicts = 1, pendingLocal = false, lastSync = null)
        assertNotNull(c1)
        assertEquals(Channel.Attention, c1.channel)
        assertEquals(Destination.Conflicts, c1.destination)
        assertEquals("Конфликт: 1 файл", c1.text)

        val c2 = notificationFor(SyncStatus.Idle, conflicts = 2, pendingLocal = false, lastSync = null)
        assertNotNull(c2)
        assertEquals(Channel.Attention, c2.channel)
        assertEquals(Destination.Conflicts, c2.destination)
        assertEquals("Конфликт: 2 файла", c2.text)

        val c5 = notificationFor(SyncStatus.Idle, conflicts = 5, pendingLocal = false, lastSync = null)
        assertNotNull(c5)
        assertEquals(Channel.Attention, c5.channel)
        assertEquals(Destination.Conflicts, c5.destination)
        assertEquals("Конфликт: 5 файлов", c5.text)
    }

    @Test
    fun `sign in required maps to login destination`() {
        val content = notificationFor(
            status = SyncStatus.Error("auth failed", SyncErrorKind.SignInRequired),
            conflicts = 0,
            pendingLocal = false,
            lastSync = null,
        )
        assertNotNull(content)
        assertEquals(Channel.Attention, content.channel)
        assertEquals(Destination.Login, content.destination)
        assertEquals("Войдите в Google снова", content.text)
    }

    @Test
    fun `aborted maps to attention`() {
        val content = notificationFor(
            status = SyncStatus.Error("remote empty", SyncErrorKind.Aborted),
            conflicts = 0,
            pendingLocal = false,
            lastSync = null,
        )
        assertNotNull(content)
        assertEquals(Channel.Attention, content.channel)
        assertEquals(Destination.Status, content.destination)
        assertEquals("Синхронизация остановлена: папка в Drive пуста", content.text)
    }

    @Test
    fun `offline with pending changes says will send later`() {
        val content = notificationFor(
            status = SyncStatus.Offline,
            conflicts = 0,
            pendingLocal = true,
            lastSync = null,
        )
        assertNotNull(content)
        assertEquals(Channel.Status, content.channel)
        assertEquals(Destination.Status, content.destination)
        assertEquals("Нет сети — изменения отправятся позже", content.text)
    }

    @Test
    fun `not configured yields no notification`() {
        val content = notificationFor(
            status = SyncStatus.NotConfigured,
            conflicts = 0,
            pendingLocal = false,
            lastSync = null,
        )
        assertNull(content)
    }
}
