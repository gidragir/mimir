package dev.mimir.desktop.tray

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.awt.image.BufferedImage
import java.util.logging.Level
import java.util.logging.Logger
import javax.imageio.ImageIO

private const val ITEM_PATH = "/StatusNotifierItem"
private const val MENU_PATH = "/MenuBar"
private const val WATCHER_NAME = "org.kde.StatusNotifierWatcher"
private const val WATCHER_PATH = "/StatusNotifierWatcher"
private const val ICON_SIZE = 64

private const val ID_OPEN = 1
private const val ID_SYNC = 2
private const val ID_QUIT = 3
private const val ID_UPDATE = 4

/**
 * Иконка в трее по протоколу StatusNotifierItem поверх чистого JVM D-Bus (Unix domain sockets, без нативных библиотек).
 * Если шины или StatusNotifierWatcher нет, остаётся [available] = false и приложение ведёт себя как без трея.
 *
 * @param busAddress адрес шины; по умолчанию сессионная шина из окружения.
 */
class DbusStatusNotifierTray(private val busAddress: String? = null) : Tray {
    @Volatile
    private var connection: DBusConnection? = null

    @Volatile
    private var item: Item? = null

    @Volatile
    override var available: Boolean = false
        private set

    override fun show(
        status: String,
        onOpen: () -> Unit,
        onSyncNow: () -> Unit,
        onQuit: () -> Unit,
        onActivate: (TrayClickAnchor?) -> Unit,
    ) {
        try {
            val bus = (if (busAddress != null) DBusConnectionBuilder.forAddress(busAddress) else DBusConnectionBuilder.forSessionBus())
                .withShared(false)
                .build()
            connection = bus
            val hasWatcher = bus.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
                .NameHasOwner(WATCHER_NAME)
            if (!hasWatcher) {
                logger.info("No $WATCHER_NAME on the session bus; running without a tray icon")
                close()
                return
            }
            val serviceName = "org.freedesktop.StatusNotifierItem-${ProcessHandle.current().pid()}-1"
            bus.requestBusName(serviceName)
            val statusItem = Item(status, onOpen, onSyncNow, onQuit, onActivate)
            item = statusItem
            bus.exportObject(ITEM_PATH, statusItem)
            bus.exportObject(MENU_PATH, statusItem.menu)
            bus.getRemoteObject(WATCHER_NAME, WATCHER_PATH, StatusNotifierWatcher::class.java)
                .RegisterStatusNotifierItem(serviceName)
            available = true
        } catch (e: Throwable) {
            logger.log(Level.INFO, "StatusNotifierItem registration failed; running without a tray icon", e)
            close()
        }
    }

    override fun update(status: String) {
        val current = item ?: return
        current.currentTitle = status
        runCatching { connection?.sendMessage(StatusNotifierItem.NewTitle(ITEM_PATH)) }
    }

    override fun setUpdate(label: String?, onClick: () -> Unit) {
        val current = item ?: return
        val revision = current.menu.setUpdate(label, onClick)
        runCatching { connection?.sendMessage(DbusMenu.LayoutUpdated(MENU_PATH, UInt32(revision.toLong()), 0)) }
    }

    override fun close() {
        available = false
        item = null
        val bus = connection ?: return
        connection = null
        runCatching { bus.disconnect() }
    }

    private class Item(
        @Volatile var currentTitle: String,
        private val onOpen: () -> Unit,
        private val onSyncNow: () -> Unit,
        private val onQuit: () -> Unit,
        private val onActivate: (TrayClickAnchor?) -> Unit,
    ) : StatusNotifierItem {
        val menu = Menu(onOpen, onSyncNow, onQuit)
        private val pixmap: List<IconPixmap> = loadIcon()

        override fun getObjectPath() = ITEM_PATH
        override fun getCategory() = "ApplicationStatus"
        override fun getId() = "mimir"
        override fun getTitle() = currentTitle
        override fun getStatus() = "Active"
        override fun getWindowId() = 0
        override fun getIconName() = ""
        override fun getIconPixmap() = pixmap
        override fun getItemIsMenu() = false
        override fun getMenu() = DBusPath(MENU_PATH)

        // Панели Wayland без глобальных координат шлют (0, 0): это «якоря нет», а не левый верхний пиксель.
        override fun Activate(x: Int, y: Int) = onActivate(if (x > 0 || y > 0) TrayClickAnchor(x, y) else null)
        override fun SecondaryActivate(x: Int, y: Int) = onOpen()
        override fun ContextMenu(x: Int, y: Int) = Unit
        override fun Scroll(delta: Int, orientation: String) = Unit
    }

    /** Минимальный dbusmenu: плоский список из трёх пунктов, сверху которых бывает четвёртый — про обновление. */
    private class Menu(
        private val onOpen: () -> Unit,
        private val onSyncNow: () -> Unit,
        private val onQuit: () -> Unit,
    ) : DbusMenu {
        private val base = listOf(ID_OPEN to "Открыть", ID_SYNC to "Синхронизировать сейчас", ID_QUIT to "Выход")

        @Volatile
        private var entries: List<Pair<Int, String>> = base

        @Volatile
        private var revision = 1

        @Volatile
        private var onUpdate: () -> Unit = {}

        /** Возвращает новую ревизию раскладки: по ней клиент трея понимает, что меню изменилось. */
        @Synchronized
        fun setUpdate(label: String?, onClick: () -> Unit): Int {
            onUpdate = onClick
            entries = if (label == null) base else listOf(ID_UPDATE to label) + base
            return ++revision
        }

        override fun getObjectPath() = MENU_PATH
        override fun getVersion() = UInt32(3)
        override fun getTextDirection() = "ltr"
        override fun getStatus() = "normal"
        override fun getIconThemePath(): List<String> = emptyList()

        private fun labelOf(label: String): Map<String, Variant<*>> = mapOf("label" to Variant(label))

        override fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): MenuLayoutReply<UInt32, MenuLayout> {
            val children = if (parentId == 0 && recursionDepth != 0) {
                entries.map { (id, label) -> Variant(MenuLayout(id, labelOf(label), emptyList())) }
            } else {
                emptyList()
            }
            val properties: Map<String, Variant<*>> = if (parentId == 0) mapOf("children-display" to Variant("submenu")) else {
                entries.firstOrNull { it.first == parentId }?.let { labelOf(it.second) } ?: emptyMap()
            }
            return MenuLayoutReply(UInt32(revision.toLong()), MenuLayout(parentId, properties, children))
        }

        override fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<MenuItemProperties> =
            entries.filter { ids.isEmpty() || it.first in ids }.map { (id, label) -> MenuItemProperties(id, labelOf(label)) }

        override fun AboutToShow(id: Int) = false

        override fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32) {
            if (eventId != "clicked") return
            when (id) {
                ID_OPEN -> onOpen()
                ID_SYNC -> onSyncNow()
                ID_QUIT -> onQuit()
                ID_UPDATE -> onUpdate()
            }
        }
    }

    private companion object {
        val logger: Logger = Logger.getLogger(DbusStatusNotifierTray::class.java.name)

        fun loadIcon(): List<IconPixmap> {
            val source = DbusStatusNotifierTray::class.java.getResourceAsStream("/tray.png")?.use { ImageIO.read(it) }
                ?: return emptyList()
            val scaled = BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB).apply {
                createGraphics().apply {
                    setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    drawImage(source, 0, 0, ICON_SIZE, ICON_SIZE, null)
                    dispose()
                }
            }
            val bytes = ByteArray(ICON_SIZE * ICON_SIZE * 4)
            for (y in 0 until ICON_SIZE) for (x in 0 until ICON_SIZE) {
                val argb = scaled.getRGB(x, y)
                val o = (y * ICON_SIZE + x) * 4
                bytes[o] = (argb ushr 24).toByte()
                bytes[o + 1] = (argb ushr 16).toByte()
                bytes[o + 2] = (argb ushr 8).toByte()
                bytes[o + 3] = argb.toByte()
            }
            return listOf(IconPixmap(ICON_SIZE, ICON_SIZE, bytes))
        }
    }
}
