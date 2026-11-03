package dev.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.mimir.core.SyncStatus
import dev.mimir.ui.model.FakeMimirUiModel
import dev.mimir.ui.model.UpdateInstallKind
import dev.mimir.ui.model.UpdateUiState
import dev.mimir.ui.screens.AboutUpdateCard
import dev.mimir.ui.screens.StatusScreen
import dev.mimir.ui.screens.UpdateTexts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class UpdateCardTest {
    private val available = UpdateUiState.Available(
        version = "0.2.0",
        notes = "• Быстрее синхронизация\n• Исправлены ошибки",
        kind = UpdateInstallKind.DownloadAndLaunch,
        releaseUrl = "https://example.org/r/0.2.0",
    )

    private fun ComposeUiTest.card(
        state: UpdateUiState,
        log: MutableList<String> = mutableListOf(),
        syncRunning: Boolean = false,
        opened: MutableList<String> = mutableListOf(),
    ) = setContent {
        MimirTheme {
            AboutUpdateCard(
                state = state,
                syncRunning = syncRunning,
                onCheck = { log += "check" },
                onDownload = { log += "download" },
                onInstall = { log += "install" },
                onOpenReleasePage = { opened += it },
                onDismiss = { log += "dismiss" },
                currentVersion = "0.1.0",
            )
        }
    }

    @Test
    fun `hidden state renders nothing`() = runComposeUiTest {
        card(UpdateUiState.Hidden)
        onNodeWithTag("update_card").assertDoesNotExist()
    }

    @Test
    fun `disabled state shows a muted note and no actions`() = runComposeUiTest {
        card(UpdateUiState.Disabled)
        onNodeWithText("v0.1.0").assertExists()
        onNodeWithText(UpdateTexts.DISABLED_NOTE).assertExists()
        onNodeWithText(UpdateTexts.CHECK).assertDoesNotExist()
    }

    @Test
    fun `up to date offers a check`() = runComposeUiTest {
        val log = mutableListOf<String>()
        card(UpdateUiState.UpToDate("0.1.0"), log)
        onNodeWithText("Установлена последняя версия · v0.1.0").assertExists()
        onNodeWithText("Проверить обновления").performClick()
        assertEquals(listOf("check"), log)
    }

    @Test
    fun `checking shows progress and no check button`() = runComposeUiTest {
        card(UpdateUiState.Checking)
        onNodeWithTag("update_progress").assertExists()
        onNodeWithText("Ищу обновления…").assertExists()
        onNodeWithText(UpdateTexts.CHECK).assertDoesNotExist()
    }

    @Test
    fun `available with installer shows badge notes and download button`() = runComposeUiTest {
        val log = mutableListOf<String>()
        card(available, log)
        onNodeWithText("Доступно обновление v0.2.0").assertExists()
        onNodeWithText("• Быстрее синхронизация\n• Исправлены ошибки").assertExists()
        onNodeWithText("Скачать и установить").assertIsEnabled().performClick()
        onNodeWithText("Скрыть").performClick()
        assertEquals(listOf("download", "dismiss"), log)
    }

    @Test
    fun `in place capability says update plugin`() = runComposeUiTest {
        card(available.copy(kind = UpdateInstallKind.InPlace))
        onNodeWithText("Обновить плагин").assertExists()
    }

    @Test
    fun `notify only opens the release page and ignores a running sync`() = runComposeUiTest {
        val opened = mutableListOf<String>()
        val log = mutableListOf<String>()
        card(available.copy(kind = UpdateInstallKind.NotifyOnly), log, syncRunning = true, opened = opened)
        onNodeWithText("Открыть страницу релиза ↗").assertIsEnabled().performClick()
        assertEquals(listOf("https://example.org/r/0.2.0"), opened)
        assertTrue(log.isEmpty())
        onNodeWithText(UpdateTexts.SYNC_RUNNING_HINT).assertDoesNotExist()
    }

    @Test
    fun `available without release url cannot open the page`() = runComposeUiTest {
        card(available.copy(kind = UpdateInstallKind.NotifyOnly, releaseUrl = null))
        onNodeWithText("Открыть страницу релиза ↗").assertIsNotEnabled()
    }

    @Test
    fun `release without notes shows no notes block`() = runComposeUiTest {
        card(available.copy(notes = null))
        onNodeWithTag("update_notes").assertDoesNotExist()
        onNodeWithText("Скачать и установить").assertExists()
    }

    @Test
    fun `downloading shows determinate progress and percent`() = runComposeUiTest {
        card(UpdateUiState.Downloading("0.2.0", 0.42f))
        onNodeWithTag("update_progress").assertExists()
        onNodeWithText("Скачиваю обновление… 42%").assertExists()
    }

    @Test
    fun `ready to install offers install`() = runComposeUiTest {
        val log = mutableListOf<String>()
        card(UpdateUiState.ReadyToInstall("0.2.0"), log)
        onNodeWithText("Обновление v0.2.0 скачано и готово к установке").assertExists()
        onNodeWithText("Установить").assertIsEnabled().performClick()
        assertEquals(listOf("install"), log)
    }

    @Test
    fun `installing shows a non blocking progress`() = runComposeUiTest {
        card(UpdateUiState.Installing("0.2.0"))
        onNodeWithText("Устанавливаю обновление…").assertExists()
        onNodeWithTag("update_progress").assertExists()
    }

    @Test
    fun `error shows human text with retry and dismiss`() = runComposeUiTest {
        val log = mutableListOf<String>()
        val message = "Не удалось скачать обновление. Проверьте подключение к интернету и повторите."
        card(UpdateUiState.Error(message), log)
        onNodeWithText(message).assertExists()
        onNodeWithText("Повторить").performClick()
        onNodeWithText("Скрыть").performClick()
        assertEquals(listOf("check", "dismiss"), log)
    }

    @Test
    fun `running sync disables download and shows the hint`() = runComposeUiTest {
        card(available, syncRunning = true)
        onNodeWithText("Скачать и установить").assertIsNotEnabled()
        onNodeWithText("Установка станет доступна после завершения синхронизации").assertExists()
    }

    @Test
    fun `running sync disables install of a downloaded update`() = runComposeUiTest {
        card(UpdateUiState.ReadyToInstall("0.2.0"), syncRunning = true)
        onNodeWithText("Установить").assertIsNotEnabled()
        onNodeWithText(UpdateTexts.SYNC_RUNNING_HINT).assertExists()
    }

    @Test
    fun `long release notes are cut and markdown is flattened`() {
        val text = UpdateTexts.notes("x".repeat(5000))!!
        assertTrue(text.length <= UpdateTexts.NOTES_LIMIT + 1)
        assertTrue(text.endsWith("…"))
        assertEquals(null, UpdateTexts.notes("  \n "))
        assertEquals("Что нового\n• пункт", UpdateTexts.notes("## Что нового\n- **пункт**"))
    }

    @Test
    fun `error text hides raw daemon wording and picks a hint`() {
        val sync = UpdateTexts.errorText(UpdateTexts.Failed.Install, "A sync run is in progress; try again when it finishes")
        assertEquals("Не удалось установить обновление. Идёт синхронизация — попробуйте, когда она закончится.", sync)
        val generic = UpdateTexts.errorText(UpdateTexts.Failed.Check, "java.net.UnknownHostException: api.github.com")
        assertEquals("Не удалось проверить обновления. Проверьте подключение к интернету и повторите.", generic)
    }

    @Test
    fun `status screen opens release pages through the uri handler and dismisses`() = runComposeUiTest {
        val model = FakeMimirUiModel(status = SyncStatus.Idle, updateState = available.copy(kind = UpdateInstallKind.NotifyOnly))
        val opened = mutableListOf<String>()
        val handler = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        setContent { CompositionLocalProvider(LocalUriHandler provides handler) { MimirTheme { StatusScreen(model) } } }
        onNodeWithText("Открыть страницу релиза ↗").performClick()
        assertEquals(listOf("https://example.org/r/0.2.0"), opened)
        onNodeWithText("Скрыть").performClick()
        waitForIdle()
        assertEquals(listOf("dismiss"), model.updateCalls)
    }

    @Test
    fun `status screen disables install while syncing`() = runComposeUiTest {
        val syncing = FakeMimirUiModel(status = SyncStatus.Syncing, updateState = available)
        setContent { MimirTheme { StatusScreen(syncing) } }
        onNodeWithText("Скачать и установить").assertIsNotEnabled()
    }

    @Test
    fun `status screen without update info has no card`() = runComposeUiTest {
        setContent { MimirTheme { StatusScreen(FakeMimirUiModel(status = SyncStatus.Idle)) } }
        onNodeWithTag("update_card").assertDoesNotExist()
    }

    @Test
    fun `status screen starts the download through the model`() = runComposeUiTest {
        val model = FakeMimirUiModel(status = SyncStatus.Idle, updateState = available)
        setContent { MimirTheme { StatusScreen(model) } }
        onNodeWithText("Скачать и установить").performClick()
        waitForIdle()
        assertEquals(listOf("download"), model.updateCalls)
    }
}
