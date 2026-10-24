package dev.mimir.desktop

import dev.mimir.core.SyncStatus
import dev.mimir.daemon.ConflictFileEntry
import dev.mimir.daemon.DaemonService
import dev.mimir.daemon.DevicePoll
import dev.mimir.daemon.InvalidPasswordException
import dev.mimir.drive.api.GoogleDeviceCodeResponse
import dev.mimir.ui.model.UiDevicePoll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class DaemonServiceUiModelTest {
    private class FakeDaemonService : DaemonService {
        override val status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
        override var vaultPath: String? = "/test/path"
        override var isLocked = true
        override var lastSyncTime: Instant? = null
        override var accountEmail: String? = "user@test.com"
        override val conflictsFlow = MutableStateFlow<List<ConflictFileEntry>>(emptyList())
        var syncTriggered = false
        var unlockPassword: String? = null
        var resolved: Triple<String, String, Boolean>? = null
        var poll: DevicePoll = DevicePoll.Pending
        var rejectPassword = false

        override suspend fun triggerSync(): Boolean { syncTriggered = true; return true }
        override suspend fun unlock(password: String) {
            if (rejectPassword) throw InvalidPasswordException("Invalid encryption password")
            unlockPassword = password
            isLocked = false
        }
        override suspend fun conflicts(): List<ConflictFileEntry> = conflictsFlow.value
        override suspend fun resolveConflict(originalPath: String, conflictPath: String, keepConflict: Boolean) {
            resolved = Triple(originalPath, conflictPath, keepConflict)
        }
        override suspend fun startDeviceLogin() = GoogleDeviceCodeResponse("dcode", "ucode", "url", 1800, 5)
        override suspend fun pollDeviceLogin(deviceCode: String) = poll
        override suspend fun configureVault(vaultPath: String) { this.vaultPath = vaultPath }
    }

    @Test
    fun `adapts DaemonService to MimirUiModel`() = runTest {
        val service = FakeDaemonService()
        val adapter = DaemonServiceUiModel(service, backgroundScope)
        assertEquals(SyncStatus.Idle, adapter.status.value)
        assertEquals("user@test.com", adapter.accountEmail.value)
        assertEquals("/test/path", adapter.vaultPath.value)
        assertTrue(adapter.triggerSync())
        assertTrue(service.syncTriggered)
    }

    @Test
    fun `polls plain properties of the service`() = runTest {
        val service = FakeDaemonService()
        val adapter = DaemonServiceUiModel(service, backgroundScope)
        service.lastSyncTime = Instant.fromEpochMilliseconds(5)
        service.isLocked = false
        advanceTimeBy(2_000)
        assertEquals(Instant.fromEpochMilliseconds(5), adapter.lastSyncTime.value)
        assertEquals(false, adapter.isLocked.value)
    }

    @Test
    fun `maps conflicts and resolutions`() = runTest {
        val service = FakeDaemonService()
        val adapter = DaemonServiceUiModel(service, backgroundScope)
        service.conflictsFlow.value = listOf(ConflictFileEntry("A.md", "A (c).md", Instant.fromEpochMilliseconds(1)))
        advanceTimeBy(100)
        assertEquals(listOf("A.md"), adapter.conflicts.value.map { it.originalPath })
        adapter.resolveConflict("A.md", "A (c).md", true)
        assertEquals(Triple("A.md", "A (c).md", true), service.resolved)
    }

    @Test
    fun `remembers the password only after a successful unlock when asked to`() = runTest {
        val service = FakeDaemonService()
        val remembered = mutableListOf<String>()
        val adapter = DaemonServiceUiModel(service, backgroundScope, rememberPassword = { remembered += it })

        service.rejectPassword = true
        assertFailsWith<InvalidPasswordException> { adapter.unlock("wrong", rememberOnDevice = true) }
        assertEquals(emptyList(), remembered)

        service.rejectPassword = false
        adapter.unlock("right", rememberOnDevice = false)
        assertEquals(emptyList(), remembered)
        adapter.unlock("right", rememberOnDevice = true)
        assertEquals(listOf("right"), remembered)
        assertEquals(false, adapter.isLocked.value)
    }

    @Test
    fun `maps device login`() = runTest {
        val service = FakeDaemonService()
        val adapter = DaemonServiceUiModel(service, backgroundScope)
        val code = adapter.startDeviceLogin()
        assertEquals("ucode", code.userCode)
        assertEquals("dcode", code.deviceCode)
        assertEquals(UiDevicePoll.Pending, adapter.pollDeviceLogin("dcode"))
        service.poll = DevicePoll.Authorized("me@x")
        assertEquals(UiDevicePoll.Authorized("me@x"), adapter.pollDeviceLogin("dcode"))
        service.poll = DevicePoll.Expired("gone")
        assertEquals(UiDevicePoll.Expired("gone"), adapter.pollDeviceLogin("dcode"))
    }
}
