package dev.mimir.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.mimir.ui.model.FakeMimirUiModel
import dev.mimir.ui.model.UiDevicePoll
import dev.mimir.ui.screens.SetupWizardScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SetupWizardScreenTest {
    @Test
    fun `step 1 allows entering vault path`() = runComposeUiTest {
        var configuredPath = ""
        val model = FakeMimirUiModel(onConfigureVault = { configuredPath = it })
        setContent { SetupWizardScreen(model, onComplete = {}) }

        onNodeWithText("Путь к хранилищу Obsidian").assertExists()
        onNodeWithText("Введите путь к папке").performTextInput("/home/user/vault")
        onNodeWithText("Продолжить").performClick()
        waitForIdle()

        assertEquals("/home/user/vault", configuredPath)
    }

    @Test
    fun `invalid vault path shows an inline error and stays on step 1`() = runComposeUiTest {
        val model = FakeMimirUiModel(onConfigureVault = { throw IllegalArgumentException("Not a directory: $it") })
        setContent { SetupWizardScreen(model, onComplete = {}) }

        onNodeWithText("Введите путь к папке").performTextInput("/nope")
        onNodeWithText("Продолжить").performClick()
        waitUntilExactlyOneExists(hasText("Not a directory: /nope"))
        onNodeWithText("Путь к хранилищу Obsidian").assertExists()
    }

    @Test
    fun `blank vault path is rejected without calling the model`() = runComposeUiTest {
        var called = false
        val model = FakeMimirUiModel(onConfigureVault = { called = true })
        setContent { SetupWizardScreen(model, onComplete = {}) }

        onNodeWithText("Продолжить").performClick()
        waitForIdle()
        assertTrue(!called)
        onNodeWithText("Укажите путь к папке хранилища").assertExists()
    }

    @Test
    fun `step 2 shows the device code while authorization is pending`() = runComposeUiTest {
        val model = FakeMimirUiModel()
        setContent { SetupWizardScreen(model, onComplete = {}, pollIntervalMillis = 10) }

        onNodeWithText("Введите путь к папке").performTextInput("/v")
        onNodeWithText("Продолжить").performClick()
        waitUntilExactlyOneExists(hasText("ABCD-EFGH"))
        onNodeWithText("Скопировать код и открыть ссылку").assertExists()
    }

    @Test
    fun `step 2 moves on to the password once authorized`() = runComposeUiTest {
        val model = FakeMimirUiModel(devicePoll = { UiDevicePoll.Authorized("me@gmail.com") })
        setContent { SetupWizardScreen(model, onComplete = {}, pollIntervalMillis = 10) }

        onNodeWithText("Введите путь к папке").performTextInput("/v")
        onNodeWithText("Продолжить").performClick()
        waitUntilExactlyOneExists(hasText("Мастер-пароль шифрования"))
    }

    @Test
    fun `step 3 reports a wrong password and completes with the right one`() = runComposeUiTest {
        var completed = false
        val model = FakeMimirUiModel(
            devicePoll = { UiDevicePoll.Authorized(null) },
            onUnlock = { password, _ -> if (password != "good") throw IllegalArgumentException("Invalid encryption password") },
        )
        setContent { SetupWizardScreen(model, onComplete = { completed = true }, pollIntervalMillis = 10) }
        onNodeWithText("Введите путь к папке").performTextInput("/v")
        onNodeWithText("Продолжить").performClick()
        waitUntilExactlyOneExists(hasText("Мастер-пароль шифрования"))

        onNodeWithText("Пароль").performTextInput("bad")
        onNodeWithText("Разблокировать").performClick()
        waitUntilExactlyOneExists(hasText("Invalid encryption password"))
        assertTrue(!completed)

        onNodeWithText("Пароль").performTextClearance()
        onNodeWithText("Пароль").performTextInput("good")
        onNodeWithText("Разблокировать").performClick()
        waitUntil { completed }
    }
}
