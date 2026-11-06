package dev.mimir.desktop.picker

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

/** Выбор папки через XDG Desktop Portal: диалог, выбранный в системе по умолчанию (GTK, KDE, GNOME…). */
class PortalFolderPicker : FolderPickerCandidate {
    override suspend fun tryPick(initialPath: String?): PickOutcome =
        try {
            withContext(Dispatchers.IO) { pickViaPortal(initialPath) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            PickOutcome.Unavailable("XDG portal file chooser unavailable", e)
        }

    private suspend fun pickViaPortal(initialPath: String?): PickOutcome {
        val bus = DBusConnectionBuilder.forSessionBus().withShared(false).build()
        try {
            val hasPortal = bus.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java).NameHasOwner(PORTAL_NAME)
            check(hasPortal) { "No $PORTAL_NAME on the session bus" }
            return openDialog(bus, initialPath)
        } finally {
            runCatching { bus.disconnect() }
        }
    }

    private suspend fun openDialog(bus: DBusConnection, initialPath: String?): PickOutcome = suspendCancellableCoroutine { continuation ->
        val token = "mimir_${System.nanoTime()}"
        val requestPath = requestPath(bus.uniqueName, token)
        // Подписка до вызова: ответ может прийти раньше, чем вернётся OpenFile.
        val subscription = bus.addSigHandler(
            PortalRequest.Response::class.java,
            DBusSigHandler<PortalRequest.Response> { signal ->
                if (signal.path != requestPath) return@DBusSigHandler
                @Suppress("UNCHECKED_CAST")
                val uris = (signal.results["uris"]?.value as? List<*>)?.filterIsInstance<String>().orEmpty()
                if (continuation.isActive) continuation.resume(outcomeFromResponse(signal.response.toInt(), uris))
            },
        )
        continuation.invokeOnCancellation { runCatching { subscription.close() } }
        try {
            val options = mutableMapOf<String, Variant<*>>(
                "handle_token" to Variant(token),
                "directory" to Variant(true),
                "modal" to Variant(true),
            )
            resolveExistingDirectory(initialPath)?.let {
                options["current_folder"] = Variant(it.absolutePath.encodeToByteArray() + 0)
            }
            bus.getRemoteObject(PORTAL_NAME, PORTAL_PATH, PortalFileChooser::class.java)
                .OpenFile("", FOLDER_PICKER_TITLE, options)
        } catch (e: Throwable) {
            runCatching { subscription.close() }
            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
        }
    }

    internal companion object {

        /** Путь запроса, на который портал пришлёт Response: `/…/request/<имя отправителя без ':' с '_' вместо '.'>/<токен>`. */
        fun requestPath(uniqueName: String, token: String): String =
            "$PORTAL_PATH/request/${uniqueName.removePrefix(":").replace('.', '_')}/$token"

        /** Отмена или пустой ответ → [PickOutcome.Cancelled]; не локальный URI → [PickOutcome.Unavailable]. */
        fun outcomeFromResponse(code: Int, uris: List<String>): PickOutcome {
            if (code != 0) return PickOutcome.Cancelled
            val raw = uris.firstOrNull() ?: return PickOutcome.Cancelled
            val uri = runCatching { URI(raw) }.getOrNull()
            if (uri == null || uri.scheme != "file") return PickOutcome.Unavailable("Portal returned a non-local URI: $raw")
            return PickOutcome.Selected(File(uri).absolutePath)
        }
    }
}
