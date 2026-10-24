package dev.mimir.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.mimir.core.StubSyncEngine
import dev.mimir.core.SyncStatus
import dev.mimir.ui.model.FakeMimirUiModel
import dev.mimir.ui.model.UiConflictEntry
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class StatusScreenTest {
    @Test
    fun `shows app title`() = runComposeUiTest {
        setContent { MimirApp(FakeMimirUiModel()) }
        onNodeWithText("Mimir").assertExists()
    }

    @Test
    fun `shows exact not connected status for unconfigured state`() = runComposeUiTest {
        setContent { MimirApp(FakeMimirUiModel(status = SyncStatus.NotConfigured)) }
        onNodeWithText("Mimir: not connected").assertExists()
    }

    @Test
    fun `engine based entry point keeps the stub status`() = runComposeUiTest {
        setContent { MimirApp(StubSyncEngine()) }
        onNodeWithText("Mimir: not connected").assertExists()
    }

    @Test
    fun `sync now button is enabled when idle and triggers sync`() = runComposeUiTest {
        var syncTriggered = false
        val model = FakeMimirUiModel(status = SyncStatus.Idle, onTriggerSync = { syncTriggered = true; true })
        setContent { MimirApp(model) }
        onNodeWithText("Синхронизировать").assertIsEnabled().performClick()
        waitForIdle()
        assertTrue(syncTriggered)
    }

    @Test
    fun `sync now button is disabled while not idle`() = runComposeUiTest {
        setContent { MimirApp(FakeMimirUiModel(status = SyncStatus.Syncing)) }
        onNodeWithText("Синхронизировать").assertIsNotEnabled()
    }

    @Test
    fun `shows conflict badge when conflicts exist`() = runComposeUiTest {
        val model = FakeMimirUiModel(
            status = SyncStatus.Idle,
            conflicts = listOf(UiConflictEntry("Notes/A.md", "Notes/A (conflicted copy).md", Instant.fromEpochMilliseconds(1000))),
        )
        setContent { MimirApp(model) }
        onNodeWithText("Конфликты: 1").assertExists()
    }

    @Test
    fun `shows setup entry when nothing is configured`() = runComposeUiTest {
        setContent { MimirApp(FakeMimirUiModel()) }
        onNodeWithText("Настроить Mimir").assertExists()
    }
}
