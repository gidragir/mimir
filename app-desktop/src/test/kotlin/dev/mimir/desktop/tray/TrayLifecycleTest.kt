package dev.mimir.desktop.tray

import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.junit.Assume.assumeTrue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Реальный dbus-daemon на отдельном сокете и поддельный StatusNotifierWatcher: ничего не трогает пользовательскую шину. */
class TrayLifecycleTest {
    private var daemon: Process? = null
    private val connections = mutableListOf<DBusConnection>()

    private class FakeWatcher : StatusNotifierWatcher {
        val registered = CopyOnWriteArrayList<String>()
        val latch = CountDownLatch(1)
        override fun getObjectPath() = "/StatusNotifierWatcher"
        override fun RegisterStatusNotifierItem(service: String) {
            registered += service
            latch.countDown()
        }
    }

    @AfterTest
    fun cleanup() {
        connections.forEach { runCatching { it.disconnect() } }
        daemon?.destroy()
    }

    private fun startBus(): String {
        val process = runCatching {
            ProcessBuilder("dbus-daemon", "--session", "--nofork", "--print-address").redirectErrorStream(true).start()
        }.getOrNull()
        assumeTrue("dbus-daemon is not installed", process != null)
        daemon = process
        return process!!.inputStream.bufferedReader().readLine().trim()
    }

    private fun connect(address: String) = DBusConnectionBuilder.forAddress(address).withShared(false).build().also { connections += it }

    @Test
    fun `without a watcher the tray reports unavailable`() {
        val address = startBus()
        val tray = DbusStatusNotifierTray(address)
        tray.show("Mimir: not connected", {}, {}, {})
        assertFalse(tray.available)
        tray.close()
    }

    @Test
    fun `registers with the watcher and dispatches activation and menu actions`() {
        val address = startBus()
        val watcherBus = connect(address)
        val watcher = FakeWatcher()
        watcherBus.requestBusName("org.kde.StatusNotifierWatcher")
        watcherBus.exportObject("/StatusNotifierWatcher", watcher)

        val opened = CountDownLatch(1)
        val synced = CountDownLatch(1)
        val quit = CountDownLatch(1)
        val tray = DbusStatusNotifierTray(address)
        try {
            tray.show("Mimir: not connected", { opened.countDown() }, { synced.countDown() }, { quit.countDown() })
            assertTrue(tray.available)
            assertTrue(watcher.latch.await(5, TimeUnit.SECONDS))
            val service = watcher.registered.single()

            val client = connect(address)
            val item = client.getRemoteObject(service, "/StatusNotifierItem", StatusNotifierItem::class.java)
            val props = client.getRemoteObject(service, "/StatusNotifierItem", Properties::class.java)
            assertEquals("mimir", props.Get<String>("org.kde.StatusNotifierItem", "Id"))
            assertEquals("Mimir: not connected", props.Get<String>("org.kde.StatusNotifierItem", "Title"))
            tray.update("Mimir: idle")
            assertEquals("Mimir: idle", props.Get<String>("org.kde.StatusNotifierItem", "Title"))
            assertTrue(props.Get<List<*>>("org.kde.StatusNotifierItem", "IconPixmap").isNotEmpty())

            item.Activate(0, 0)
            assertTrue(opened.await(5, TimeUnit.SECONDS))

            val menu = client.getRemoteObject(service, "/MenuBar", DbusMenu::class.java)
            val layout = menu.GetLayout(0, -1, emptyList())
            assertEquals(listOf(1, 2, 3), layout.layout.children.map { (it.value as Array<*>)[0] as Int })
            menu.Event(2, "clicked", Variant(0), UInt32(0))
            assertTrue(synced.await(5, TimeUnit.SECONDS))
            menu.Event(3, "clicked", Variant(0), UInt32(0))
            assertTrue(quit.await(5, TimeUnit.SECONDS))
        } finally {
            tray.close()
        }
    }
}
