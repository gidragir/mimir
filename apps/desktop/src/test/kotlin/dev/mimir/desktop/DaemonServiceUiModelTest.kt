@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.mimir.desktop

import dev.mimir.client.DaemonConnectionStatus
import dev.mimir.client.DaemonDeviceCodeDto
import dev.mimir.client.DaemonLoginMethod
import dev.mimir.client.DaemonLoginPoll
import dev.mimir.client.DaemonLoginStart
import dev.mimir.client.DaemonService
import dev.mimir.client.DaemonVaultInspectionDto
import dev.mimir.core.ConflictFileEntry
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import dev.mimir.client.InvalidPasswordException
import dev.mimir.client.UpdateApplyDto
import dev.mimir.client.UpdateCommandException
import dev.mimir.client.UpdateErrorCode
import dev.mimir.ui.model.UpdateRetry
import dev.mimir.client.UpdateStatus
import dev.mimir.client.UpdateStatusDto
import dev.mimir.ui.model.UpdateInstallKind
import dev.mimir.ui.model.UpdateUiState
import kotlinx.coroutines.test.runCurrent
import dev.mimir.ui.model.UiConflictEntry
import dev.mimir.ui.model.UiDeviceCode
import dev.mimir.ui.model.UiLoginMethod
import dev.mimir.ui.model.UiLoginPoll
import dev.mimir.ui.model.UiLoginStart
import dev.mimir.ui.model.UiVaultInspection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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
        var poll: DaemonLoginPoll = DaemonLoginPoll.Pending
        val started = mutableListOf<DaemonLoginMethod>()
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
        override suspend fun startLogin(method: DaemonLoginMethod): DaemonLoginStart {
            started += method
            return when (method) {
                DaemonLoginMethod.SystemAccount -> DaemonLoginStart.Started
                DaemonLoginMethod.Browser -> DaemonLoginStart.OpenUrl("https://auth.example/login")
                DaemonLoginMethod.DeviceCode -> DaemonLoginStart.ShowDeviceCode(DaemonDeviceCodeDto("dcode", "ucode", "url", 1800, 5))
            }
        }
        override suspend fun pollLogin(): DaemonLoginPoll = poll
        var fileCount = 0
        var inspection = DaemonVaultInspectionDto(0, 0, false)
        override suspend fun configureVault(vaultPath: String): Int { this.vaultPath = vaultPath; return fileCount }
        override suspend fun inspectVault(): DaemonVaultInspectionDto = inspection
        var methods: Set<DaemonLoginMethod> = emptySet()
        override suspend fun loginMethods(): Set<DaemonLoginMethod> = methods

        var update = UpdateStatusDto("0.1.0", UpdateStatus.IDLE)
        var updateFailure: Exception? = null
        var updateGets = 0
        val updateCommands = mutableListOf<String>()
        var checkResult: UpdateStatusDto? = null
        override suspend fun getUpdate(): UpdateStatusDto {
            updateGets++
            updateFailure?.let { throw it }
            return update
        }
        override suspend fun checkUpdates(): UpdateStatusDto {
            updateCommands += "check"
            updateFailure?.let { throw it }
            return (checkResult ?: update).also { update = it }
        }
        override suspend fun downloadUpdate() {
            updateCommands += "download"
            updateFailure?.let { throw it }
            update = update.copy(status = UpdateStatus.DOWNLOADING, downloadProgress = 0.1f)
        }
        override suspend fun applyUpdate(): UpdateApplyDto {
            updateCommands += "apply"
            updateFailure?.let { throw it }
            return UpdateApplyDto("installer_launched", "")
        }
    }

    private val availableDto = UpdateStatusDto(
        currentVersion = "0.1.0",
        status = UpdateStatus.AVAILABLE,
        latestVersion = "0.2.0",
        releaseNotes = "## Новое\n- **Быстрее**",
        capability = "DOWNLOAD_AND_LAUNCH",
        htmlUrl = "https://example.org/r",
    )

    @Test
    fun `maps every update status to the ui state`() = runTest {
        val service = FakeDaemonService()
        val model = DaemonServiceUiModel(service, backgroundScope)
        assertEquals(UpdateUiState.Hidden, model.updateState.value)

        suspend fun stateFor(dto: UpdateStatusDto): UpdateUiState {
            service.update = dto
            advanceTimeBy(31_000)
            return model.updateState.value
        }
        assertEquals(UpdateUiState.Disabled, stateFor(UpdateStatusDto("0.1.0", UpdateStatus.DISABLED)))
        assertEquals(UpdateUiState.Unknown("0.1.0"), stateFor(UpdateStatusDto("0.1.0", UpdateStatus.IDLE)), "never checked")
        assertEquals(
            UpdateUiState.UpToDate("0.1.0"),
            stateFor(UpdateStatusDto("0.1.0", UpdateStatus.IDLE, lastCheckedAt = "2026-11-03T10:00:00Z")),
            "checked and nothing found",
        )
        assertEquals(UpdateUiState.Checking, stateFor(UpdateStatusDto("0.1.0", UpdateStatus.CHECKING)))
        assertEquals(
            UpdateUiState.Available("0.2.0", "Новое\n• Быстрее", UpdateInstallKind.DownloadAndLaunch, "https://example.org/r"),
            stateFor(availableDto),
        )
        assertEquals(UpdateInstallKind.NotifyOnly, (stateFor(availableDto.copy(capability = "NOTIFY_ONLY")) as UpdateUiState.Available).kind)
        assertEquals(UpdateInstallKind.InPlace, (stateFor(availableDto.copy(capability = "IN_PLACE_REPLACE")) as UpdateUiState.Available).kind)
        assertEquals(
            UpdateUiState.Downloading("0.2.0", 0.5f),
            stateFor(availableDto.copy(status = UpdateStatus.DOWNLOADING, downloadProgress = 0.5f)),
        )
        assertEquals(UpdateUiState.ReadyToInstall("0.2.0"), stateFor(availableDto.copy(status = UpdateStatus.READY_TO_INSTALL)))
        assertEquals(UpdateUiState.Installing("0.2.0"), stateFor(availableDto.copy(status = UpdateStatus.INSTALLING)))
        val error = stateFor(
            availableDto.copy(status = UpdateStatus.ERROR, errorMessage = "raw english", errorCode = "CHECKSUM_MISMATCH", failedStep = "DOWNLOAD"),
        )
        assertEquals(
            UpdateUiState.Error("Не удалось скачать обновление. Файл повредился при загрузке — скачайте его заново.", UpdateRetry.Download),
            error,
        )
        assertEquals(
            UpdateUiState.Error("Не удалось установить обновление. Попробуйте ещё раз позже.", UpdateRetry.Install),
            stateFor(availableDto.copy(status = UpdateStatus.ERROR, errorCode = "FAILED", failedStep = "INSTALL")),
        )
        // повторять то же самое бессмысленно: нужна свежая проверка
        assertEquals(
            UpdateUiState.Error(
                "Для этой системы нет готового установщика. Откройте страницу релиза и обновитесь вручную.",
                UpdateRetry.Check,
            ),
            stateFor(availableDto.copy(status = UpdateStatus.ERROR, errorCode = "NO_INSTALLABLE_FILE", failedStep = "DOWNLOAD")),
        )
    }

    @Test
    fun `polls slowly when idle, quickly while downloading, and never busy loops`() = runTest {
        val service = FakeDaemonService()
        DaemonServiceUiModel(service, backgroundScope)
        runCurrent()
        assertEquals(1, service.updateGets)
        advanceTimeBy(29_000)
        assertEquals(1, service.updateGets, "idle cadence is 30 s")
        advanceTimeBy(2_000)
        assertEquals(2, service.updateGets)

        service.update = availableDto.copy(status = UpdateStatus.DOWNLOADING, downloadProgress = 0.3f)
        advanceTimeBy(30_000) // подхватили на очередном медленном тике
        val atStart = service.updateGets
        advanceTimeBy(5_500)
        val polled = service.updateGets - atStart
        assertTrue(polled in 4..7, "about 1 poll per second while downloading, was $polled")

        service.update = availableDto.copy(status = UpdateStatus.READY_TO_INSTALL)
        advanceTimeBy(1_100)
        val ready = service.updateGets
        advanceTimeBy(10_000)
        assertEquals(ready, service.updateGets, "back to the slow cadence when terminal")
    }

    @Test
    fun `an unreachable daemon backs off to the idle cadence even while downloading`() = runTest {
        val service = FakeDaemonService()
        service.update = availableDto.copy(status = UpdateStatus.DOWNLOADING, downloadProgress = 0.3f)
        val model = DaemonServiceUiModel(service, backgroundScope)
        runCurrent()
        assertIs<UpdateUiState.Downloading>(model.updateState.value)
        advanceTimeBy(2_100) // частый ритм, пока демон отвечает
        assertTrue(service.updateGets >= 3)
        service.updateFailure = java.io.IOException("down")
        advanceTimeBy(1_100) // следующий опрос упал
        val atFailure = service.updateGets
        advanceTimeBy(20_000)
        assertEquals(atFailure, service.updateGets, "no 1 s hammering while the daemon is down")
        advanceTimeBy(11_000)
        assertEquals(atFailure + 1, service.updateGets, "retries on the 30 s cadence")
        assertIs<UpdateUiState.Downloading>(model.updateState.value, "keeps the last known state")
    }

    @Test
    fun `an unreachable daemon keeps the last known update state`() = runTest {
        val service = FakeDaemonService()
        service.update = availableDto
        val model = DaemonServiceUiModel(service, backgroundScope)
        runCurrent()
        assertIs<UpdateUiState.Available>(model.updateState.value)
        service.updateFailure = java.io.IOException("down")
        advanceTimeBy(61_000)
        assertIs<UpdateUiState.Available>(model.updateState.value)
    }

    @Test
    fun `check shows progress then the result and maps failures to human text`() = runTest {
        val service = FakeDaemonService()
        val model = DaemonServiceUiModel(service, backgroundScope)
        runCurrent()
        service.checkResult = availableDto
        model.checkForUpdates()
        assertIs<UpdateUiState.Available>(model.updateState.value)
        assertEquals(listOf("check"), service.updateCommands)

        service.updateFailure = IllegalStateException("java.net.UnknownHostException: api.github.com")
        model.checkForUpdates()
        assertEquals(
            UpdateUiState.Error("Не удалось проверить обновления. Проверьте подключение к интернету и повторите.", UpdateRetry.Check),
            model.updateState.value,
        )
        // периодический опрос не стирает ошибку команды, пока состояние демона не сдвинулось
        service.updateFailure = null
        service.update = availableDto
        advanceTimeBy(31_000)
        assertIs<UpdateUiState.Error>(model.updateState.value)
        model.dismissUpdate()
        assertIs<UpdateUiState.Available>(model.updateState.value)
    }

    @Test
    fun `download starts in the background and progress arrives through polling`() = runTest {
        val service = FakeDaemonService()
        service.update = availableDto
        val model = DaemonServiceUiModel(service, backgroundScope)
        runCurrent()
        model.downloadUpdate()
        assertEquals(listOf("download"), service.updateCommands)
        assertEquals(UpdateUiState.Downloading("0.2.0", 0.1f), model.updateState.value)
        service.update = availableDto.copy(status = UpdateStatus.READY_TO_INSTALL)
        advanceTimeBy(1_100)
        assertEquals(UpdateUiState.ReadyToInstall("0.2.0"), model.updateState.value)
    }

    @Test
    fun `apply and refused apply during a sync`() = runTest {
        val service = FakeDaemonService()
        service.update = availableDto.copy(status = UpdateStatus.READY_TO_INSTALL)
        val model = DaemonServiceUiModel(service, backgroundScope)
        runCurrent()
        service.updateFailure = UpdateCommandException(UpdateErrorCode.SYNC_RUNNING)
        model.applyUpdate()
        assertEquals(
            UpdateUiState.Error("Идёт синхронизация — установите обновление, когда она закончится.", UpdateRetry.Install),
            model.updateState.value,
        )
        service.updateFailure = null
        model.applyUpdate()
        assertEquals(listOf("apply", "apply"), service.updateCommands)
        assertEquals(UpdateUiState.ReadyToInstall("0.2.0"), model.updateState.value)
    }

    @Test
    fun `dismiss hides the offer until a newer version appears`() = runTest {
        val service = FakeDaemonService()
        service.update = availableDto
        val model = DaemonServiceUiModel(service, backgroundScope)
        runCurrent()
        model.dismissUpdate()
        assertEquals(UpdateUiState.Unknown("0.1.0"), model.updateState.value, "hiding an offer must not claim the app is current")
        advanceTimeBy(31_000)
        assertEquals(UpdateUiState.Unknown("0.1.0"), model.updateState.value)
        service.update = availableDto.copy(latestVersion = "0.3.0")
        advanceTimeBy(31_000)
        assertIs<UpdateUiState.Available>(model.updateState.value)
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
    fun `maps login methods starts and polls to ui types`() = runTest {
        val service = FakeDaemonService()
        val adapter = DaemonServiceUiModel(service, backgroundScope)
        service.methods = DaemonLoginMethod.entries.toSet()
        assertEquals(UiLoginMethod.entries.toSet(), adapter.loginMethods())

        assertEquals(UiLoginStart.OpenUrl("https://auth.example/login"), adapter.startLogin(UiLoginMethod.Browser))
        assertEquals(UiLoginStart.Started, adapter.startLogin(UiLoginMethod.SystemAccount))
        assertEquals(listOf(DaemonLoginMethod.Browser, DaemonLoginMethod.SystemAccount), service.started)

        assertEquals(UiLoginPoll.Pending, adapter.pollLogin())
        service.poll = DaemonLoginPoll.Authorized("me@x")
        assertEquals(UiLoginPoll.Authorized("me@x"), adapter.pollLogin())
        service.poll = DaemonLoginPoll.Failed("Вход отменён")
        assertEquals(UiLoginPoll.Failed("Вход отменён"), adapter.pollLogin())
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
        val code = (adapter.startLogin(UiLoginMethod.DeviceCode) as UiLoginStart.ShowDeviceCode).code
        assertEquals(UiDeviceCode("ucode", "url", 1800, "dcode"), code)
    }
}
