package dev.mimir.desktop.tray

import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusBoundProperty
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

// Имена методов и свойств — как в спецификации StatusNotifierItem / dbusmenu, поэтому с заглавной буквы.
@Suppress("FunctionName")
@DBusInterfaceName("org.kde.StatusNotifierWatcher")
interface StatusNotifierWatcher : DBusInterface {
    fun RegisterStatusNotifierItem(service: String)
}

@Suppress("FunctionName")
@DBusInterfaceName("org.kde.StatusNotifierItem")
interface StatusNotifierItem : DBusInterface {
    @DBusBoundProperty(name = "Category") fun getCategory(): String
    @DBusBoundProperty(name = "Id") fun getId(): String
    @DBusBoundProperty(name = "Title") fun getTitle(): String
    @DBusBoundProperty(name = "Status") fun getStatus(): String
    @DBusBoundProperty(name = "WindowId") fun getWindowId(): Int
    @DBusBoundProperty(name = "IconName") fun getIconName(): String
    @DBusBoundProperty(name = "IconPixmap") fun getIconPixmap(): List<IconPixmap>
    @DBusBoundProperty(name = "ItemIsMenu") fun getItemIsMenu(): Boolean
    @DBusBoundProperty(name = "Menu") fun getMenu(): org.freedesktop.dbus.DBusPath

    fun Activate(x: Int, y: Int)
    fun SecondaryActivate(x: Int, y: Int)
    fun ContextMenu(x: Int, y: Int)
    fun Scroll(delta: Int, orientation: String)

    class NewTitle(path: String) : DBusSignal(path)
}

/** ARGB32 в сетевом порядке байт, как требует StatusNotifierItem. */
class IconPixmap(
    @field:Position(0) val width: Int,
    @field:Position(1) val height: Int,
    @field:Position(2) val data: ByteArray,
) : Struct()

@Suppress("FunctionName")
@DBusInterfaceName("com.canonical.dbusmenu")
interface DbusMenu : DBusInterface {
    @DBusBoundProperty(name = "Version") fun getVersion(): UInt32
    @DBusBoundProperty(name = "TextDirection") fun getTextDirection(): String
    @DBusBoundProperty(name = "Status") fun getStatus(): String
    @DBusBoundProperty(name = "IconThemePath") fun getIconThemePath(): List<String>

    fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): MenuLayoutReply<UInt32, MenuLayout>
    fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<MenuItemProperties>
    fun AboutToShow(id: Int): Boolean
    fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32)
}

/** Составной ответ D-Bus: dbus-java берёт типы значений из параметров дженерика. */
class MenuLayoutReply<A, B>(
    @field:Position(0) val revision: A,
    @field:Position(1) val layout: B,
) : Tuple()

class MenuLayout(
    @field:Position(0) val id: Int,
    @field:Position(1) val properties: @JvmSuppressWildcards Map<String, Variant<*>>,
    @field:Position(2) val children: @JvmSuppressWildcards List<Variant<*>>,
) : Struct()

class MenuItemProperties(
    @field:Position(0) val id: Int,
    @field:Position(1) val properties: @JvmSuppressWildcards Map<String, Variant<*>>,
) : Struct()
