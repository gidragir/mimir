package dev.mimir.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.mimir.core.CompletedItem
import dev.mimir.core.StubSyncEngine
import dev.mimir.core.SyncItem
import dev.mimir.core.SyncItemAction
import dev.mimir.core.SyncPhase
import dev.mimir.core.SyncProblem
import dev.mimir.core.SyncProblemKind
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import dev.mimir.ui.model.FakeMimirUiModel
import dev.mimir.ui.screens.StatusScreen
import dev.mimir.ui.model.UiConflictEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class StatusScreenTest {
    private fun progressOf(p: SyncProgress) = MutableStateFlow<SyncProgress?>(p)

    @Test
    fun `shows app title`() = runComposeUiTest {
        setContent { MimirApp(FakeMimirUiModel()) }
        onNodeWithText("Mimir").assertExists()
    }

    @Test
    fun `status screen renders under the obsidian theme`() = runComposeUiTest {
        setContent { MimirTheme { StatusScreen(FakeMimirUiModel()) } }
        onNodeWithText(dev.mimir.core.BuildConfig.APP_NAME).assertIsDisplayed()
    }

    @Test
    fun notConnectedStillShowsExactLabel() = runComposeUiTest {
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
        onNodeWithText("Синхронизировать сейчас").assertIsEnabled().performClick()
        waitForIdle()
        assertTrue(syncTriggered)
    }

    @Test
    fun `sync now button is disabled while not idle`() = runComposeUiTest {
        setContent { MimirApp(FakeMimirUiModel(status = SyncStatus.Syncing)) }
        onNodeWithText("Синхронизировать сейчас").assertIsNotEnabled()
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

    @Test
    fun scanningShowsCheckedCount() = runComposeUiTest {
        val progress = SyncProgress(phase = SyncPhase.Scanning, startedAt = Clock.System.now() - 12.seconds, scanned = 340)
        setContent { MimirApp(FakeMimirUiModel(status = SyncStatus.Syncing, progress = progressOf(progress))) }
        onNodeWithText("Ищу изменения…").assertExists()
        onNodeWithText("Проверено 340 файлов").assertExists()
    }

    @Test
    fun scanningShowsFolderCountWhenKnown() = runComposeUiTest {
        val progress = SyncProgress(
            phase = SyncPhase.Scanning,
            startedAt = Clock.System.now() - 12.seconds,
            scanned = 340,
            foldersScanned = 120,
        )
        setContent { MimirApp(FakeMimirUiModel(status = SyncStatus.Syncing, progress = progressOf(progress))) }
        onNodeWithText("Проверено 340 файлов в 120 папках").assertExists()
    }

    @Test
    fun applyingShowsCountersEtaCurrentAndUpcoming() = runComposeUiTest {
        val now = Clock.System.now()
        val progress = SyncProgress(
            phase = SyncPhase.Applying,
            startedAt = now - 80.seconds,
            applyingStartedAt = now - 60.seconds,
            total = 87,
            done = 12,
            bytesTotal = 100,
            bytesDone = 25, // 60 с * 75 / 25 = 180 с
            current = SyncItem("daily/2026-10-24.md", SyncItemAction.Upload),
            upcoming = (1..4).map { SyncItem("next/$it.md", SyncItemAction.Download) },
        )
        setContent { MimirApp(FakeMimirUiModel(status = SyncStatus.Syncing, progress = progressOf(progress))) }
        onNodeWithText("Синхронизация…").assertExists()
        onNodeWithText("12 из 87 файлов").assertExists()
        onNodeWithText("осталось ≈ 3 мин", substring = true).assertExists()
        onNodeWithText("⬆ Отправляю в Google Drive: daily/2026-10-24.md").assertExists()
        onNodeWithText("и ещё 70", substring = true).assertExists()
        onNodeWithText("⬇ Загружаю: next/1.md").assertExists()
    }

    @Test
    fun problemsShownAboveWithHumanHint() = runComposeUiTest {
        val progress = SyncProgress(
            phase = SyncPhase.Applying,
            startedAt = Clock.System.now(),
            total = 3,
            done = 1,
            problems = listOf(SyncProblem("locked/note.md", SyncItemAction.Upload, SyncProblemKind.ReadFailed, "java.io.IOException: sharing violation")),
        )
        setContent { MimirApp(FakeMimirUiModel(status = SyncStatus.Syncing, progress = progressOf(progress))) }
        onNodeWithText("⚠ 1 файл не удалось синхронизировать").assertExists()
        onNodeWithText("locked/note.md").assertExists()
        onNodeWithText("не удалось прочитать файл — возможно, он открыт в другой программе").assertExists()
        onNodeWithText("Mimir попробует ещё раз при следующей синхронизации").assertExists()
        onNodeWithText("java.io.IOException: sharing violation").assertDoesNotExist()
        onNodeWithText("Подробнее").performClick()
        onNodeWithText("java.io.IOException: sharing violation").assertExists()
    }

    @Test
    fun completedListCollapsedByDefault() = runComposeUiTest {
        val now = Clock.System.now()
        val progress = SyncProgress(
            phase = SyncPhase.Applying,
            startedAt = now,
            total = 20,
            done = 12,
            completed = (1..12).map { CompletedItem("done/file-$it.md", SyncItemAction.Upload, now) },
        )
        setContent { MimirApp(FakeMimirUiModel(status = SyncStatus.Syncing, progress = progressOf(progress))) }
        onNodeWithText("done/file-1.md", substring = true).assertDoesNotExist()
        onNodeWithText("Готово (12)", substring = true).performClick()
        onNodeWithText("done/file-1.md", substring = true).assertExists()
    }

    @Test
    fun finishedShowsSummaryAndSyncButton() = runComposeUiTest {
        val started = Clock.System.now() - 300.seconds
        val progress = SyncProgress(
            phase = SyncPhase.Finished,
            startedAt = started,
            finishedAt = started + 245.seconds,
            total = 87,
            done = 87,
        )
        setContent { MimirApp(FakeMimirUiModel(status = SyncStatus.Idle, accountEmail = "a@b.c", progress = progressOf(progress))) }
        onNodeWithText("✓ Всё синхронизировано").assertExists()
        onNodeWithText("87 файлов за 4 мин 05 с", substring = true).assertExists()
        onNodeWithText("Синхронизировать сейчас").assertIsEnabled()
    }

    @Test
    fun offlineShowsReassurance() = runComposeUiTest {
        setContent { MimirApp(FakeMimirUiModel(status = SyncStatus.Offline, accountEmail = "a@b.c")) }
        onNodeWithText("Нет подключения к интернету").assertExists()
        onNodeWithText("Mimir продолжит, когда сеть появится. Изменения на этом компьютере сохранены.").assertExists()
    }

    @Test
    fun authErrorOffersSignIn() = runComposeUiTest {
        var setupOpened = false
        setContent {
            MimirTheme {
                dev.mimir.ui.screens.StatusScreen(
                    FakeMimirUiModel(status = SyncStatus.Error("invalid_grant"), accountEmail = null),
                    onOpenSetup = { setupOpened = true },
                )
            }
        }
        onNodeWithText("Нужно снова войти в Google").assertExists()
        onNodeWithText("Войти через Google").assertIsEnabled().performClick()
        waitForIdle()
        assertTrue(setupOpened)
    }

    @Test
    fun errorWithAccountStillOffersSignIn() = runComposeUiTest {
        var setupOpened = false
        setContent {
            MimirTheme {
                dev.mimir.ui.screens.StatusScreen(
                    FakeMimirUiModel(status = SyncStatus.Error("invalid_grant"), accountEmail = "user@example.com"),
                    onOpenSetup = { setupOpened = true },
                )
            }
        }
        onNodeWithText("Не удалось синхронизировать").assertExists()
        onNodeWithText("Подробнее").assertExists()
        onNodeWithText("Войти через Google").assertIsEnabled().performClick()
        waitForIdle()
        assertTrue(setupOpened)
    }
}
