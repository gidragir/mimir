package dev.mimir.desktop.ipc

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import dev.mimir.desktop.PlatformFocus

sealed interface IpcAction {
    data class OpenUri(val uri: String, val activationToken: String? = null) : IpcAction
    data object Activate : IpcAction
}

class SingleInstanceManager(socketPath: Path? = null) : AutoCloseable {
    private val socketPath: Path = socketPath ?: defaultSocketPath()
    private var serverChannel: ServerSocketChannel? = null
    private var listenerThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val shutdownHook = Thread {
        try {
            Files.deleteIfExists(this.socketPath)
        } catch (_: IOException) {
        }
    }

    var onActivationRequest: ((IpcAction) -> Unit)? = null

    fun acquirePrimary(args: Array<String>): Boolean {
        val address = UnixDomainSocketAddress.of(socketPath)

        val connected = try {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(address)

                val uri = args.firstOrNull { it.startsWith("mimir://") }
                val message = if (uri != null) {
                    val token = System.getenv("XDG_ACTIVATION_TOKEN") ?: ""
                    "ACTION:OPEN_URI\nURI:$uri\nXDG_ACTIVATION_TOKEN:$token\nEND\n"
                } else {
                    "ACTION:ACTIVATE\nEND\n"
                }
                channel.write(ByteBuffer.wrap(message.toByteArray(StandardCharsets.UTF_8)))

                val responseBuffer = ByteBuffer.allocate(32)
                channel.read(responseBuffer)
                true
            }
        } catch (_: IOException) {
            false
        }

        if (connected) {
            PlatformFocus.allowForegroundTransfer()
            return false
        }

        try {
            Files.deleteIfExists(socketPath)
        } catch (_: IOException) {
        }

        socketPath.parent?.let { Files.createDirectories(it) }

        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(address)
        serverChannel = server
        running.set(true)

        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook)
        } catch (_: IllegalStateException) {
        }

        registerMacOpenUriHandler()
        startListener(server)
        return true
    }

    private fun startListener(server: ServerSocketChannel) {
        listenerThread = Thread {
            while (running.get() && server.isOpen) {
                try {
                    val client = server.accept() ?: continue
                    handleClient(client)
                } catch (_: ClosedChannelException) {
                    break
                } catch (_: IOException) {
                    if (!running.get()) break
                }
            }
        }.apply {
            isDaemon = true
            name = "mimir-ipc-listener"
            start()
        }
    }

    private fun handleClient(client: SocketChannel) {
        try {
            client.use { ch ->
                val baos = ByteArrayOutputStream()
                val buf = ByteBuffer.allocate(512)
                var foundEnd = false
                while (ch.read(buf) > 0) {
                    buf.flip()
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    baos.write(bytes)
                    buf.clear()
                    val text = baos.toString(StandardCharsets.UTF_8.name())
                    if (text.contains("END")) {
                        foundEnd = true
                        break
                    }
                }
                if (foundEnd) {
                    val text = baos.toString(StandardCharsets.UTF_8.name())
                    val action = parseIpcAction(text)
                    if (action != null) {
                        onActivationRequest?.invoke(action)
                    }
                    ch.write(ByteBuffer.wrap("OK\n".toByteArray(StandardCharsets.UTF_8)))
                }
            }
        } catch (_: IOException) {
        }
    }

    private fun parseIpcAction(text: String): IpcAction? {
        val lines = text.lines().map { it.trim() }
        val actionLine = lines.firstOrNull { it.startsWith("ACTION:") } ?: return null
        return when (actionLine.removePrefix("ACTION:").trim()) {
            "OPEN_URI" -> {
                val uri = lines.firstOrNull { it.startsWith("URI:") }?.removePrefix("URI:")?.trim() ?: return null
                val token = lines.firstOrNull { it.startsWith("XDG_ACTIVATION_TOKEN:") }
                    ?.removePrefix("XDG_ACTIVATION_TOKEN:")?.trim()?.ifEmpty { null }
                IpcAction.OpenUri(uri, token)
            }
            "ACTIVATE" -> IpcAction.Activate
            else -> null
        }
    }

    private fun registerMacOpenUriHandler() {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                val desktop = java.awt.Desktop.getDesktop()
                if (desktop.isSupported(java.awt.Desktop.Action.APP_OPEN_URI)) {
                    desktop.setOpenURIHandler { event ->
                        event.uri?.toString()?.let { uriStr ->
                            onActivationRequest?.invoke(IpcAction.OpenUri(uriStr))
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
        } catch (_: IllegalStateException) {
        }
        try {
            serverChannel?.close()
        } catch (_: IOException) {
        }
        listenerThread?.interrupt()
        try {
            Files.deleteIfExists(socketPath)
        } catch (_: IOException) {
        }
    }

    companion object {
        fun defaultSocketPath(): Path {
            val os = System.getProperty("os.name", "").lowercase()
            return when {
                os.contains("win") -> {
                    val localAppData = System.getenv("LOCALAPPDATA")
                        ?: (System.getProperty("user.home") + "\\AppData\\Local")
                    Paths.get(localAppData, "mimir", "mimir-ui.sock")
                }
                os.contains("mac") -> {
                    val home = System.getProperty("user.home")
                    Paths.get(home, "Library", "Caches", "mimir", "mimir-ui.sock")
                }
                else -> {
                    val runtimeDir = System.getenv("XDG_RUNTIME_DIR")
                    if (!runtimeDir.isNullOrBlank()) {
                        Paths.get(runtimeDir, "mimir-ui.sock")
                    } else {
                        val home = System.getProperty("user.home")
                        Paths.get(home, ".cache", "mimir", "mimir-ui.sock")
                    }
                }
            }
        }
    }
}
