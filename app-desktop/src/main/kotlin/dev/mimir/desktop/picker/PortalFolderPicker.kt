package dev.mimir.desktop.picker

import dev.mimir.ui.picker.PlatformFolderPicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.io.File
import java.net.URI
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.resume

private const val PORTAL_NAME = "org.freedesktop.portal.Desktop"
private const val PORTAL_PATH = "/org/freedesktop/portal/desktop"

@Suppress("FunctionName")
@DBusInterfaceName("org.freedesktop.portal.FileChooser")
interface PortalFileChooser : DBusInterface {
    fun OpenFile(parentWindow: String, title: String, options: @JvmSuppressWildcards Map<String, Variant<*>>): org.freedesktop.dbus.DBusPath
}

@DBusInterfaceName("org.freedesktop.portal.Request")
interface PortalRequest : DBusInterface {
    class Response(path: String, val response: UInt32, val results: @JvmSuppressWildcards Map<String, Variant<*>>) : DBusSignal(path, response, results)
}

/**
 * Выбор папки через XDG Desktop Portal: открывается диалог, выбранный в системе по умолчанию (GTK, KDE, GNOME…).
 * Если портала нет или вызов не удался, работает [fallback] (Swing).
 */
class PortalFolderPicker(private val fallback: PlatformFolderPicker = AwtFolderPicker()) : PlatformFolderPicker {
    override suspend fun pickDirectory(initialPath: String?): String? {
        val outcome = try {
            withContext(Dispatchers.IO) { pickViaPortal(initialPath) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.log(Level.INFO, "XDG portal file chooser unavailable; falling back to the Swing dialog", e)
            return fallback.pickDirectory(initialPath)
        }
        return outcome
    }

    private suspend fun pickViaPortal(initialPath: String?): String? {
        val bus = DBusConnectionBuilder.forSessionBus().withShared(false).build()
        try {
            val hasPortal = bus.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java).NameHasOwner(PORTAL_NAME)
            check(hasPortal) { "No $PORTAL_NAME on the session bus" }
            return openDialog(bus, initialPath)
        } finally {
            runCatching { bus.disconnect() }
        }
    }

    private suspend fun openDialog(bus: DBusConnection, initialPath: String?): String? = suspendCancellableCoroutine { continuation ->
        val token = "mimir_${System.nanoTime()}"
        val requestPath = requestPath(bus.uniqueName, token)
        // Подписка до вызова: ответ может прийти раньше, чем вернётся OpenFile.
        val subscription = bus.addSigHandler(
            PortalRequest.Response::class.java,
            DBusSigHandler<PortalRequest.Response> { signal ->
                if (signal.path != requestPath) return@DBusSigHandler
                @Suppress("UNCHECKED_CAST")
                val uris = (signal.results["uris"]?.value as? List<*>)?.filterIsInstance<String>().orEmpty()
                if (continuation.isActive) continuation.resume(pathFromResponse(signal.response.toInt(), uris))
            },
        )
        continuation.invokeOnCancellation { runCatching { subscription.close() } }
        try {
            val options = mutableMapOf<String, Variant<*>>(
                "handle_token" to Variant(token),
                "directory" to Variant(true),
                "modal" to Variant(true),
            )
            initialPath?.let(::File)?.takeIf { it.isDirectory }?.let {
                options["current_folder"] = Variant(it.absolutePath.encodeToByteArray() + 0)
            }
            bus.getRemoteObject(PORTAL_NAME, PORTAL_PATH, PortalFileChooser::class.java)
                .OpenFile("", "Выберите папку хранилища", options)
        } catch (e: Throwable) {
            runCatching { subscription.close() }
            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
        }
    }

    internal companion object {
        private val logger = Logger.getLogger(PortalFolderPicker::class.java.name)

        /** Путь запроса, на который портал пришлёт Response: `/…/request/<имя отправителя без ':' с '_' вместо '.'>/<токен>`. */
        fun requestPath(uniqueName: String, token: String): String =
            "$PORTAL_PATH/request/${uniqueName.removePrefix(":").replace('.', '_')}/$token"

        /** `null` — выбор отменён (или портал вернул не локальный путь); иначе первый выбранный каталог. */
        fun pathFromResponse(code: Int, uris: List<String>): String? {
            if (code != 0) return null
            val uri = uris.firstOrNull()?.let { runCatching { URI(it) }.getOrNull() } ?: return null
            if (uri.scheme != "file") return null
            return File(uri).absolutePath
        }
    }
}

/** Системный диалог на Linux (через портал), иначе Swing. */
fun defaultFolderPicker(): PlatformFolderPicker =
    if (System.getProperty("os.name").lowercase().contains("linux")) PortalFolderPicker() else AwtFolderPicker()
