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
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.mimir.core.SyncStatus
import dev.mimir.ui.model.FakeMimirUiModel
import dev.mimir.client.UpdateErrorCode
import dev.mimir.client.UpdateStatus
import dev.mimir.client.UpdateStatusDto
import dev.mimir.ui.model.toUi
import dev.mimir.ui.model.UpdateInstallKind
import dev.mimir.ui.model.UpdateRetry
import dev.mimir.ui.model.UpdateTexts
import dev.mimir.ui.model.UpdateUiState
import dev.mimir.ui.screens.AboutUpdateCard
import dev.mimir.ui.screens.StatusScreen
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
        onNodeWithText("Скачать").assertIsEnabled().performClick()
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
        onNodeWithText("Скачать").assertExists()
    }

    @Test
    fun `downloading shows determinate progress and percent`() = runComposeUiTest {
        card(UpdateUiState.Downloading("0.2.0", 0.42f))
        onNodeWithTag("update_progress").assertExists()
        onNodeWithText("Скачиваю обновление v0.2.0… 42%").assertExists()
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
    fun `error details are collapsed by default and toggle on click`() = runComposeUiTest {
        val raw = "Request timeout has expired [url=https://github.com/x/Mimir-0.3.1.msi, request_timeout=15000 ms]"
        card(UpdateUiState.Error("Не удалось скачать обновление. Попробуйте ещё раз позже.", UpdateRetry.Download, details = raw))
        onNodeWithText(raw).assertDoesNotExist()
        onNodeWithText(UpdateTexts.details(expanded = false)).performClick()
        onNodeWithText(raw).assertExists()
        onNodeWithText(UpdateTexts.DETAILS_HINT).assertExists()
        onNodeWithText(UpdateTexts.details(expanded = true)).performClick()
        onNodeWithText(raw).assertDoesNotExist()
    }

    @Test
    fun `error without details has no details toggle`() = runComposeUiTest {
        card(UpdateUiState.Error("Обновление уже скачивается."))
        onNodeWithText(UpdateTexts.details(expanded = false)).assertDoesNotExist()
    }

    @Test
    fun `raw daemon message becomes error details`() {
        val dto = UpdateStatusDto(
            "0.1.0", UpdateStatus.ERROR, latestVersion = "0.2.0",
            errorMessage = "HTTP 404 for https://x/m.msi", errorCode = "HTTP_ERROR", failedStep = "DOWNLOAD",
        )
        assertEquals("HTTP 404 for https://x/m.msi", (dto.toUi() as UpdateUiState.Error).details)
        assertEquals(null, (dto.copy(errorMessage = " ").toUi() as UpdateUiState.Error).details)
    }

    @Test
    fun `running sync disables download and shows the hint`() = runComposeUiTest {
        card(available, syncRunning = true)
        onNodeWithText("Скачать").assertIsNotEnabled()
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
    fun `every error code has its own human text and none is the generic network hint except network`() {
        val network = UpdateTexts.errorText(UpdateRetry.Check, UpdateErrorCode.NETWORK)
        assertEquals("Не удалось проверить обновления. Проверьте подключение к интернету и повторите.", network)
        UpdateErrorCode.entries.filter { it != UpdateErrorCode.NETWORK }.forEach {
            val text = UpdateTexts.errorText(UpdateRetry.Download, it)
            assertTrue("подключение к интернету" !in text, "$it -> $text")
            assertTrue(text.none { c -> c in 'a'..'z' }, "no raw english for $it: $text")
        }
        assertEquals(
            "Для этой системы нет готового установщика. Откройте страницу релиза и обновитесь вручную.",
            UpdateTexts.errorText(UpdateRetry.Download, UpdateErrorCode.NO_INSTALLABLE_FILE),
        )
        assertEquals("Обновление уже скачивается.", UpdateTexts.errorText(UpdateRetry.Download, UpdateErrorCode.DOWNLOAD_IN_PROGRESS))
        assertEquals("Проверка обновлений выключена.", UpdateTexts.errorText(UpdateRetry.Check, UpdateErrorCode.UPDATES_DISABLED))
        assertEquals(
            "Идёт синхронизация — установите обновление, когда она закончится.",
            UpdateTexts.errorText(UpdateRetry.Install, UpdateErrorCode.SYNC_RUNNING),
        )
    }

    @Test
    fun `download failure codes explain what happened`() {
        assertEquals(
            "Не удалось скачать обновление. Файл обновления сейчас недоступен на сервере — попробуйте позже.",
            UpdateTexts.errorText(UpdateRetry.Download, UpdateErrorCode.HTTP_ERROR),
        )
        assertEquals(
            "Не удалось скачать обновление. Не получилось проверить подлинность файла — проверьте обновления ещё раз чуть позже.",
            UpdateTexts.errorText(UpdateRetry.Download, UpdateErrorCode.NO_CHECKSUM),
        )
        assertEquals(
            "Не удалось скачать обновление. Файл не удалось сохранить на диск — проверьте свободное место и повторите.",
            UpdateTexts.errorText(UpdateRetry.Download, UpdateErrorCode.DISK),
        )
    }

    @Test
    fun `missing checksum is retried by a fresh check and an unknown code reads as a generic failure`() {
        fun error(code: String?) = UpdateStatusDto("0.1.0", UpdateStatus.ERROR, latestVersion = "0.2.0", errorCode = code, failedStep = "DOWNLOAD").toUi()
        assertEquals(UpdateRetry.Check, (error("NO_CHECKSUM") as UpdateUiState.Error).retry)
        assertEquals(UpdateRetry.Download, (error("HTTP_ERROR") as UpdateUiState.Error).retry)
        val unknown = error("SOMETHING_FROM_A_NEWER_DAEMON") as UpdateUiState.Error
        assertEquals(UpdateTexts.errorText(UpdateRetry.Download, UpdateErrorCode.FAILED), unknown.message)
        assertEquals(UpdateRetry.Download, unknown.retry)
    }

    @Test
    fun `unknown state shows the version and a check button but never claims the app is current`() = runComposeUiTest {
        val log = mutableListOf<String>()
        card(UpdateUiState.Unknown("0.1.0"), log)
        onNodeWithText("v0.1.0").assertExists()
        onNodeWithText(UpdateTexts.NOT_CHECKED).assertExists()
        onNodeWithText("Установлена последняя версия", substring = true).assertDoesNotExist()
        onNodeWithText("Проверить обновления").performClick()
        assertEquals(listOf("check"), log)
    }

    private fun retryLog(retry: UpdateRetry): List<String> {
        val log = mutableListOf<String>()
        runComposeUiTest {
            card(UpdateUiState.Error("Ошибка", retry), log)
            onNodeWithText("Повторить").performClick()
        }
        return log
    }

    @Test
    fun `retry repeats the step that failed`() {
        assertEquals(listOf("check"), retryLog(UpdateRetry.Check))
        assertEquals(listOf("download"), retryLog(UpdateRetry.Download))
        assertEquals(listOf("install"), retryLog(UpdateRetry.Install))
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
        onNodeWithText("Скачать").assertIsNotEnabled()
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
        onNodeWithText("Скачать").performClick()
        waitForIdle()
        assertEquals(listOf("download"), model.updateCalls)
    }
}
