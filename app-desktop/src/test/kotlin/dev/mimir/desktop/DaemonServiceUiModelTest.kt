package dev.mimir.desktop

import dev.mimir.client.DaemonBrowserLoginPoll
import dev.mimir.client.DaemonConnectionStatus
import dev.mimir.client.DaemonDeviceCodeDto
import dev.mimir.client.DaemonDevicePoll
import dev.mimir.client.DaemonLoginMethod
import dev.mimir.client.DaemonService
import dev.mimir.client.DaemonVaultInspectionDto
import dev.mimir.core.ConflictFileEntry
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import dev.mimir.daemon.InvalidPasswordException
import dev.mimir.ui.model.UiBrowserPoll
import dev.mimir.ui.model.UiConflictEntry
import dev.mimir.ui.model.UiDeviceCode
import dev.mimir.ui.model.UiDevicePoll
import dev.mimir.ui.model.UiLoginMethod
import dev.mimir.ui.model.UiVaultInspection
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
        override val progress = MutableStateFlow<SyncProgress?>(null)
        override var vaultPath: String? = "/test/path"
        override var isLocked = true
        override var lastSyncTime: Instant? = null
        override var accountEmail: String? = "user@test.com"
        override val conflictsFlow = MutableStateFlow<List<ConflictFileEntry>>(emptyList())
        override val connectionStatus = MutableStateFlow(DaemonConnectionStatus.Connected)
        var syncTriggered = false
        var unlockPassword: String? = null
        var resolved: Triple<String, String, Boolean>? = null
        var poll: DaemonDevicePoll = DaemonDevicePoll.Pending
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
        override suspend fun startDeviceLogin() = DaemonDeviceCodeDto("dcode", "ucode", "url", 1800, 5)
        override suspend fun pollDeviceLogin(deviceCode: String): DaemonDevicePoll = poll
        var fileCount = 0
        var inspection = DaemonVaultInspectionDto(0, 0, false)
        override suspend fun configureVault(vaultPath: String): Int { this.vaultPath = vaultPath; return fileCount }
        override suspend fun inspectVault(): DaemonVaultInspectionDto = inspection
        var browserPoll: DaemonBrowserLoginPoll = DaemonBrowserLoginPoll.Pending
        var methods: Set<DaemonLoginMethod> = emptySet()
        override suspend fun startBrowserLogin(): String = "https://auth.example/login"
        override suspend fun pollBrowserLogin(): DaemonBrowserLoginPoll = browserPoll
        override suspend fun loginMethods(): Set<DaemonLoginMethod> = methods
    }

    @Test
    fun `maps vault file count and inspection to ui types`() = runTest {
        val service = FakeDaemonService()
        val adapter = DaemonServiceUiModel(service, backgroundScope)
        service.fileCount = 7
        service.inspection = DaemonVaultInspectionDto(7, 9, true)
        assertEquals(7, adapter.configureVault("/vault"))
        assertEquals(UiVaultInspection(7, 9, true), adapter.inspectVault())
    }

    @Test
    fun `maps browser login methods and polls to ui types`() = runTest {
        val service = FakeDaemonService()
        val adapter = DaemonServiceUiModel(service, backgroundScope)
        service.methods = setOf(DaemonLoginMethod.Browser, DaemonLoginMethod.DeviceCode)
        assertEquals(setOf(UiLoginMethod.Browser, UiLoginMethod.DeviceCode), adapter.loginMethods())
        assertEquals("https://auth.example/login", adapter.startBrowserLogin())
        assertEquals(UiBrowserPoll.Pending, adapter.pollBrowserLogin())
        service.browserPoll = DaemonBrowserLoginPoll.Authorized("me@x")
        assertEquals(UiBrowserPoll.Authorized("me@x"), adapter.pollBrowserLogin())
        service.browserPoll = DaemonBrowserLoginPoll.Failed("Вход отменён")
        assertEquals(UiBrowserPoll.Failed("Вход отменён"), adapter.pollBrowserLogin())
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
        service.poll = DaemonDevicePoll.Authorized("me@x")
        assertEquals(UiDevicePoll.Authorized("me@x"), adapter.pollDeviceLogin("dcode"))
        service.poll = DaemonDevicePoll.Expired("gone")
        assertEquals(UiDevicePoll.Expired("gone"), adapter.pollDeviceLogin("dcode"))
    }
}
