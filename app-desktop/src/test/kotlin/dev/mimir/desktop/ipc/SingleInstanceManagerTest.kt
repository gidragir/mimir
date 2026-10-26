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

        val action = withTimeout(5.seconds) { actionDeferred.await() }
        assertEquals(IpcAction.OpenUri("mimir://authorized"), action)

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
}
