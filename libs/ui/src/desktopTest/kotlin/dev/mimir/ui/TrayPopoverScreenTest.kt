package dev.mimir.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.mimir.core.SyncErrorKind
import dev.mimir.core.SyncItem
import dev.mimir.core.SyncItemAction
import dev.mimir.core.SyncPhase
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import dev.mimir.core.testing.TestClock
import dev.mimir.ui.model.FakeMimirUiModel
import dev.mimir.ui.model.UiConflictEntry
import dev.mimir.ui.model.UpdateInstallKind
import dev.mimir.ui.model.UpdateUiState
import dev.mimir.ui.screens.TrayPopoverScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class TrayPopoverScreenTest {
    private val fixedNow = Instant.fromEpochSeconds(1_700_000_600L)
    private val fixedClock = TestClock(fixedNow)

    @Test
    fun `startup state shows loading indicator and allows open and quit`() = runComposeUiTest {
        var opened = false
        var quit = false
        setContent {
            MimirTheme {
                TrayPopoverScreen(
                    model = null,
                    startupError = null,
                    onOpenMainWindow = { opened = true },
                    onQuit = { quit = true },
                    clock = fixedClock,
                )
            }
        }
        onNodeWithText("Запуск…").assertIsDisplayed()
        onNodeWithText("Открыть Mimir").performClick()
        assertTrue(opened)
        onNodeWithText("Выход").performClick()
        assertTrue(quit)
    }

    @Test
    fun `startup error state displays error message`() = runComposeUiTest {
        setContent {
            MimirTheme {
                TrayPopoverScreen(
                    model = null,
                    startupError = "Порт занят",
                    onOpenMainWindow = {},
                    onQuit = {},
                    clock = fixedClock,
                )
            }
        }
        onNodeWithText("Не удалось запустить Mimir: Порт занят").assertIsDisplayed()
    }

    @Test
    fun `unconfigured or locked vault shows setup button and hides sync button`() = runComposeUiTest {
        var opened = false
        val model = FakeMimirUiModel(status = SyncStatus.NotConfigured, vaultPath = null, isLocked = true)
        setContent {
            MimirTheme {
                TrayPopoverScreen(
                    model = model,
                    onOpenMainWindow = { opened = true },
                    onQuit = {},
                    clock = fixedClock,
                )
            }
        }
        onNodeWithText("Настроить Mimir").assertIsDisplayed().performClick()
        assertTrue(opened)
        onNodeWithText("Синхронизировать сейчас").assertDoesNotExist()
    }

    @Test
    fun `idle state displays vault name, path, last sync time and triggers sync`() = runComposeUiTest {
        var syncTriggers = 0
        val model = FakeMimirUiModel(
            status = SyncStatus.Idle,
            onTriggerSync = { syncTriggers++; true },
            vaultPath = "/home/user/Documents/ObsidianVault",
            isLocked = false,
            lastSyncTime = fixedNow,
        )
        setContent {
            MimirTheme {
                TrayPopoverScreen(
                    model = model,
                    onOpenMainWindow = {},
                    onQuit = {},
                    clock = fixedClock,
                )
            }
        }
        onNodeWithText("ObsidianVault").assertIsDisplayed()
        onNodeWithText("/home/user/Documents/ObsidianVault").assertIsDisplayed()
        onNodeWithText("✓ Всё синхронизировано").assertIsDisplayed()
        onNodeWithText("Синхронизировать сейчас").assertIsEnabled().performClick()
        waitForIdle()
        assertEquals(1, syncTriggers)
    }

    @Test
    fun `syncing applying state shows progress counter, current file and disables sync button`() = runComposeUiTest {
        val progress = SyncProgress(
            phase = SyncPhase.Applying,
            startedAt = fixedNow,
            done = 7,
            total = 20,
            current = SyncItem("notes/daily.md", SyncItemAction.Upload),
        )
        val model = FakeMimirUiModel(
            status = SyncStatus.Syncing,
            vaultPath = "/home/user/Notes",
            isLocked = false,
            progress = MutableStateFlow(progress),
        )
        setContent {
            MimirTheme {
                TrayPopoverScreen(
                    model = model,
                    onOpenMainWindow = {},
                    onQuit = {},
                    clock = fixedClock,
                )
            }
        }
        onNodeWithText("7 из 20 файлов").assertIsDisplayed()
        onNodeWithText("⬆ Отправляю в Google Drive: notes/daily.md").assertIsDisplayed()
        // «Синхронизация…» — заголовок фазы и подпись заблокированной кнопки
        onAllNodesWithText("Синхронизация…").assertCountEquals(2)
        onNodeWithTag("mimir_button_loading", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `offline state shows offline hint and disables sync button`() = runComposeUiTest {
        val model = FakeMimirUiModel(
            status = SyncStatus.Offline,
            vaultPath = "/home/user/Notes",
            isLocked = false,
        )
        setContent {
            MimirTheme {
                TrayPopoverScreen(
                    model = model,
                    onOpenMainWindow = {},
                    onQuit = {},
                    clock = fixedClock,
                )
            }
        }
        onNodeWithText("Нет подключения к интернету").assertIsDisplayed()
        onNodeWithText("Синхронизировать сейчас").assertIsNotEnabled()
    }

    @Test
    fun `error state shows canonical title without raw exception message`() = runComposeUiTest {
        val model = FakeMimirUiModel(
            status = SyncStatus.Error("java.io.IOException: secret stacktrace", SyncErrorKind.SignInRequired),
            vaultPath = "/home/user/Notes",
            isLocked = false,
        )
        setContent {
            MimirTheme {
                TrayPopoverScreen(
                    model = model,
                    onOpenMainWindow = {},
                    onQuit = {},
                    clock = fixedClock,
                )
            }
        }
        onNodeWithText("Войдите в Google снова").assertIsDisplayed()
        onNodeWithText("java.io.IOException: secret stacktrace").assertDoesNotExist()
    }

    @Test
    fun `conflicts and update banners are shown and open main window on click`() = runComposeUiTest {
        var openCalls = 0
        val model = FakeMimirUiModel(
            status = SyncStatus.Idle,
            vaultPath = "/home/user/Notes",
            isLocked = false,
            conflicts = listOf(UiConflictEntry("a.md", "a.conflict.md", fixedNow)),
            updateState = UpdateUiState.Available(
                version = "0.4.0",
                notes = null,
                kind = UpdateInstallKind.DownloadAndLaunch,
                releaseUrl = "https://example.com",
            ),
        )
        setContent {
            MimirTheme {
                TrayPopoverScreen(
                    model = model,
                    onOpenMainWindow = { openCalls++ },
                    onQuit = {},
                    clock = fixedClock,
                )
            }
        }
        onNodeWithTag("popover_conflicts_banner").assertIsDisplayed().performClick()
        assertEquals(1, openCalls)
        onNodeWithTag("popover_update_banner").assertIsDisplayed().performClick()
        assertEquals(2, openCalls)
        assertFalse(model.status.value is SyncStatus.Error)
    }

    @Test
    fun `popover updates progress reactively when progress flow emits`() = runComposeUiTest {
        val progressFlow = MutableStateFlow<SyncProgress?>(
            SyncProgress(phase = SyncPhase.Scanning, startedAt = fixedNow, scanned = 15, foldersScanned = 3),
        )
        val model = FakeMimirUiModel(
            status = SyncStatus.Syncing,
            vaultPath = "/home/user/Notes",
            isLocked = false,
            progress = progressFlow,
        )
        setContent {
            MimirTheme {
                TrayPopoverScreen(
                    model = model,
                    onOpenMainWindow = {},
                    onQuit = {},
                    clock = fixedClock,
                )
            }
        }
        onNodeWithText("Проверено 15 файлов в 3 папках").assertIsDisplayed()

        progressFlow.value = SyncProgress(
            phase = SyncPhase.Applying,
            startedAt = fixedNow,
            done = 4,
            total = 10,
            current = SyncItem("notes/hello.md", SyncItemAction.Download),
        )
        waitForIdle()
        onNodeWithText("4 из 10 файлов").assertIsDisplayed()
        onNodeWithText("⬇ Загружаю: notes/hello.md").assertIsDisplayed()
    }
}
