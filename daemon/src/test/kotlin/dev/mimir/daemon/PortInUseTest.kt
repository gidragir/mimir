package dev.mimir.daemon

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.ServerSocket
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
            System.setErr(PrintStream(captured, true))
            val code = try {
                runDaemon(DaemonConfig("127.0.0.1", port, null, null))
            } finally {
                System.setErr(originalErr)
            }
            val message = captured.toString().trim()
            assertEquals(2, code)
            assertEquals("Port $port on 127.0.0.1 is already in use", message)
            assertTrue("Exception" !in message && "\tat " !in message)
        }
    }
}
