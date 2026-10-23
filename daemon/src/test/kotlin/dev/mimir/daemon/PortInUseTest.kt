package dev.mimir.daemon

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PortInUseTest {
    @Test
    fun `busy port yields exit code 2 and readable message`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { busy ->
            val port = busy.localPort
            val captured = ByteArrayOutputStream()
            val originalErr = System.err
            val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
            val uncaught = CopyOnWriteArrayList<Throwable>()
            Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
            System.setErr(PrintStream(captured, true))
            val code = try {
                runDaemon(DaemonConfig("127.0.0.1", port, null, null)).also { Thread.sleep(300) }
            } finally {
                System.setErr(originalErr)
                Thread.setDefaultUncaughtExceptionHandler(originalHandler)
            }
            assertEquals(emptyList(), uncaught, "bind failure must not leak as an uncaught exception")
            val message = captured.toString().trim()
            assertEquals(2, code)
            assertEquals("Port $port on 127.0.0.1 is already in use", message)
            assertTrue("Exception" !in message && "\tat " !in message)
        }
    }
}
