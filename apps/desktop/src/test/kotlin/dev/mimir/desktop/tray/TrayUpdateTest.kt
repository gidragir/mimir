@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.mimir.desktop.tray

import dev.mimir.ui.model.UpdateInstallKind
import dev.mimir.ui.model.UpdateUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrayUpdateTest {
    private val available = UpdateUiState.Available("0.2.0", null, UpdateInstallKind.DownloadAndLaunch, "https://example.org/r")

    private class RecordingTray : Tray {
        override val available = true
        val labels = mutableListOf<String?>()
        var onClick: () -> Unit = {}
        override fun show(status: String, onOpen: () -> Unit, onSyncNow: () -> Unit, onQuit: () -> Unit, onActivate: (TrayClickAnchor?) -> Unit) = Unit
        override fun update(status: String) = Unit
        override fun setUpdate(label: String?, onClick: () -> Unit) {
            labels += label
            this.onClick = onClick
        }
        override fun close() = Unit
    }

    @Test
    fun `label exists only while an update is available or downloaded`() {
        assertEquals("Доступно обновление v0.2.0", trayUpdateLabel(available))
        assertEquals("Доступно обновление v0.2.0", trayUpdateLabel(UpdateUiState.ReadyToInstall("0.2.0")))
        listOf(
            UpdateUiState.Hidden,
            UpdateUiState.Disabled,
            UpdateUiState.UpToDate("0.1.0"),
            UpdateUiState.Checking,
            UpdateUiState.Downloading("0.2.0", 0.5f),
            UpdateUiState.Installing("0.2.0"),
            UpdateUiState.Error("Не удалось"),
        ).forEach { assertNull(trayUpdateLabel(it), it.toString()) }
    }

    @Test
    fun `binding adds and removes the tray item as the state changes and click opens the window`() = runTest {
        val tray = RecordingTray()
        val state = MutableStateFlow<UpdateUiState>(UpdateUiState.UpToDate("0.1.0"))
        var opened = 0
        val job = backgroundScope.launch { bindUpdateItem(tray, state) { opened++ } }
        runCurrent()
        state.value = available
        runCurrent()
        state.value = UpdateUiState.Downloading("0.2.0", 0.2f)
        runCurrent()
        state.value = UpdateUiState.ReadyToInstall("0.2.0")
        runCurrent()
        state.value = UpdateUiState.UpToDate("0.2.0")
        runCurrent()
        assertEquals(listOf(null, "Доступно обновление v0.2.0", null, "Доступно обновление v0.2.0", null), tray.labels)
        state.value = available
        runCurrent()
        tray.onClick()
        assertEquals(1, opened)
        job.cancel()
    }

    @Test
    fun `repeated identical states do not touch the tray again`() = runTest {
        val tray = RecordingTray()
        val state = MutableStateFlow<UpdateUiState>(available)
        backgroundScope.launch { bindUpdateItem(tray, state) {} }
        runCurrent()
        state.value = available.copy(notes = "changed notes")
        runCurrent()
        assertEquals(listOf<String?>("Доступно обновление v0.2.0"), tray.labels)
    }
}
