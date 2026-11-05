package dev.mimir.desktop.ipc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SingleInstanceManagerTest {
    @Test
    fun testPrimaryAcquiresLockAndReceivesOpenUriFromSecondary() = runTest {
        val tempSocket = Files.createTempFile("mimir-test-sock-", ".sock").apply { Files.delete(this) }
        val primary = SingleInstanceManager(socketPath = tempSocket)
        val actionDeferred = CompletableDeferred<IpcAction>()
        primary.onActivationRequest = { actionDeferred.complete(it) }

        assertTrue(primary.acquirePrimary(emptyArray()))

        val secondary = SingleInstanceManager(socketPath = tempSocket)
        val acquiredBySecondary = secondary.acquirePrimary(arrayOf("mimir://authorized"))
        assertFalse(acquiredBySecondary)

        val expectedToken = System.getenv("XDG_ACTIVATION_TOKEN")?.ifEmpty { null }
        val action = withTimeout(5.seconds) { actionDeferred.await() }
        assertEquals(IpcAction.OpenUri("mimir://authorized", activationToken = expectedToken), action)

        primary.close()
    }

    @Test
    fun testSecondaryWithoutArgsSendsActivateAction() = runTest {
        val tempSocket = Files.createTempFile("mimir-test-sock-", ".sock").apply { Files.delete(this) }
        val primary = SingleInstanceManager(socketPath = tempSocket)
        val actionDeferred = CompletableDeferred<IpcAction>()
        primary.onActivationRequest = { actionDeferred.complete(it) }

        assertTrue(primary.acquirePrimary(emptyArray()))

        val secondary = SingleInstanceManager(socketPath = tempSocket)
        assertFalse(secondary.acquirePrimary(emptyArray()))

        val action = withTimeout(5.seconds) { actionDeferred.await() }
        assertEquals(IpcAction.Activate, action)

        primary.close()
    }

    @Test
    fun testRecoversFromStaleSocketFile() {
        val tempSocket = Files.createTempFile("mimir-test-stale-", ".sock")
        // Файл существует, но сервер не слушает (stale socket)
        val manager = SingleInstanceManager(socketPath = tempSocket)
        assertTrue(manager.acquirePrimary(emptyArray()))
        manager.close()
    }

    @Test
    fun testQueuesActivationRequestsBeforeHandlerIsSet() = runTest {
        val tempSocket = Files.createTempFile("mimir-test-queue-", ".sock").apply { Files.delete(this) }
        val primary = SingleInstanceManager(socketPath = tempSocket)
        assertTrue(primary.acquirePrimary(emptyArray()))

        val secondary = SingleInstanceManager(socketPath = tempSocket)
        assertFalse(secondary.acquirePrimary(arrayOf("mimir://authorized")))

        // onActivationRequest устанавливается позже (после холодного старта UI)
        val actionDeferred = CompletableDeferred<IpcAction>()
        primary.onActivationRequest = { actionDeferred.complete(it) }

        val expectedToken = System.getenv("XDG_ACTIVATION_TOKEN")?.ifEmpty { null }
        val action = withTimeout(5.seconds) { actionDeferred.await() }
        assertEquals(IpcAction.OpenUri("mimir://authorized", activationToken = expectedToken), action)

        primary.close()
    }

    @Test
    fun testDoesNotDeleteSocketWhenConnectionSucceedsButIOFails() {
        val tempSocket = Files.createTempFile("mimir-test-abort-", ".sock").apply { Files.delete(this) }
        val address = java.net.UnixDomainSocketAddress.of(tempSocket)
        val mockServer = java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)
        mockServer.bind(address)

        val serverThread = Thread {
            try {
                val client = mockServer.accept()
                // Закрываем клиентский сокет сразу, провоцируя IOException у secondary
                client.close()
            } catch (_: Exception) {}
        }.apply { start() }

        val secondary = SingleInstanceManager(socketPath = tempSocket)
        val acquired = secondary.acquirePrimary(arrayOf("mimir://authorized"))
        assertFalse(acquired, "Secondary process must not acquire primary if server accepted connection")
        assertTrue(Files.exists(tempSocket), "Socket file must not be deleted if server accepted connection")

        mockServer.close()
        serverThread.join()
        Files.deleteIfExists(tempSocket)
    }
}
