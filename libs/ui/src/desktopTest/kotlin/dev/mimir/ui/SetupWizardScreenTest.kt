package dev.mimir.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.mimir.ui.model.FakeMimirUiModel
import dev.mimir.ui.model.UiLoginPoll
import dev.mimir.ui.model.UiLoginMethod
import dev.mimir.ui.model.UiVaultInspection
import dev.mimir.ui.picker.LocalFolderPicker
import dev.mimir.ui.picker.PlatformFolderPicker
import dev.mimir.ui.platform.LocalStorageAccess
import dev.mimir.ui.platform.LocalVaultDiscovery
import dev.mimir.ui.platform.StorageAccess
import dev.mimir.ui.platform.VaultDiscovery
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
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
        val model = FakeMimirUiModel(loginPoll = { UiLoginPoll.Authorized("me@gmail.com") })
        setContent { SetupWizardScreen(model, onComplete = {}, pollIntervalMillis = 10) }

        onNodeWithText("Введите путь к папке").performTextInput("/v")
        onNodeWithText("Продолжить").performClick()
        waitUntilExactlyOneExists(hasText("Мастер-пароль шифрования"))
    }

    @Test
    fun `step 3 reports a wrong password and completes with the right one`() = runComposeUiTest {
        var completed = false
        val model = FakeMimirUiModel(
            loginPoll = { UiLoginPoll.Authorized(null) },
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

    private class RecordingUriHandler : UriHandler {
        val opened = mutableListOf<String>()
        override fun openUri(uri: String) { opened += uri }
    }

    private val both = setOf(UiLoginMethod.Browser, UiLoginMethod.DeviceCode)

    private fun androidx.compose.ui.test.ComposeUiTest.toGoogleStep(model: FakeMimirUiModel, handler: UriHandler = RecordingUriHandler()) {
        setContent { CompositionLocalProvider(LocalUriHandler provides handler) { SetupWizardScreen(model, onComplete = {}, pollIntervalMillis = 10) } }
        onNodeWithText("Введите путь к папке").performTextInput("/v")
        onNodeWithText("Продолжить").performClick()
    }

    @Test
    fun browserMethodShowsBigButtonAndCodeLink() = runComposeUiTest {
        toGoogleStep(FakeMimirUiModel(loginMethods = both))
        waitUntilExactlyOneExists(hasText("Войти через Google"))
        onNodeWithText("Войти с помощью кода на другом устройстве").assertExists()
    }

    @Test
    fun onlyDeviceCodeShowsCodeScreenDirectly() = runComposeUiTest {
        toGoogleStep(FakeMimirUiModel(loginMethods = setOf(UiLoginMethod.DeviceCode)))
        waitUntilExactlyOneExists(hasText("ABCD-EFGH"))
        onNodeWithText("Войти через Google").assertDoesNotExist()
    }

    @Test
    fun noMethodsShowsNotConfiguredMessage() = runComposeUiTest {
        toGoogleStep(FakeMimirUiModel(loginMethods = emptySet()))
        waitUntilExactlyOneExists(hasText("Вход в Google не настроен в этой сборке"))
    }

    @Test
    fun browserLoginWaitsThenAdvances() = runComposeUiTest {
        val handler = RecordingUriHandler()
        var authorized = false
        val model = FakeMimirUiModel(
            loginMethods = both,
            browserAuthUrl = "https://auth.example/login",
            loginPoll = { if (authorized) UiLoginPoll.Authorized("me@x") else UiLoginPoll.Pending },
        )
        toGoogleStep(model, handler)
        waitUntilExactlyOneExists(hasText("Войти через Google"))
        onNodeWithText("Войти через Google").performClick()
        waitUntilExactlyOneExists(hasText("Ждём подтверждения в браузере…"))
        assertEquals(listOf("https://auth.example/login"), handler.opened)
        authorized = true
        waitUntilExactlyOneExists(hasText("Мастер-пароль шифрования"))
    }

    @Test
    fun browserLoginFailureShowsReasonAndRetry() = runComposeUiTest {
        val model = FakeMimirUiModel(loginMethods = both, loginPoll = { UiLoginPoll.Failed("Вход отменён") })
        toGoogleStep(model)
        waitUntilExactlyOneExists(hasText("Войти через Google"))
        onNodeWithText("Войти через Google").performClick()
        waitUntilExactlyOneExists(hasText("Вход отменён"))
        onNodeWithText("Попробовать снова").assertExists()
    }

    @Test
    fun systemAccountIsPreferredAndPolledUntilAuthorized() = runComposeUiTest {
        val handler = RecordingUriHandler()
        var authorized = false
        val model = FakeMimirUiModel(
            loginMethods = setOf(UiLoginMethod.DeviceCode, UiLoginMethod.Browser, UiLoginMethod.SystemAccount),
            loginPoll = { if (authorized) UiLoginPoll.Authorized("me@x") else UiLoginPoll.Pending },
        )
        toGoogleStep(model, handler)
        waitUntilExactlyOneExists(hasText("Войти через Google"))
        onNodeWithText("Войти через Google").performClick()
        waitUntilExactlyOneExists(hasText("Ждём подтверждения входа…"))
        assertEquals(listOf(UiLoginMethod.SystemAccount), model.startedLogins)
        assertTrue(handler.opened.isEmpty())
        authorized = true
        waitUntilExactlyOneExists(hasText("Мастер-пароль шифрования"))
    }

    @Test
    fun deviceCodeFailureOffersRetry() = runComposeUiTest {
        val model = FakeMimirUiModel(loginPoll = { UiLoginPoll.Failed("expired_token") })
        toGoogleStep(model)
        waitUntilExactlyOneExists(hasText("Код истёк: expired_token"))
        onNodeWithText("Попробовать снова").performClick()
        waitUntil { model.startedLogins.size >= 2 }
        assertEquals(setOf(UiLoginMethod.DeviceCode), model.startedLogins.toSet())
    }

    @Test
    fun cancelReturnsToButton() = runComposeUiTest {
        toGoogleStep(FakeMimirUiModel(loginMethods = both))
        waitUntilExactlyOneExists(hasText("Войти через Google"))
        onNodeWithText("Войти через Google").performClick()
        waitUntilExactlyOneExists(hasText("Ждём подтверждения в браузере…"))
        onNodeWithText("Отмена").performClick()
        waitUntilExactlyOneExists(hasText("Войти через Google"))
    }

    @Test
    fun failingOpenUriOffersCopyLink() = runComposeUiTest {
        val handler = object : UriHandler { override fun openUri(uri: String) = throw IllegalStateException("no browser") }
        toGoogleStep(FakeMimirUiModel(loginMethods = both, browserAuthUrl = "https://auth.example/login"), handler)
        waitUntilExactlyOneExists(hasText("Войти через Google"))
        onNodeWithText("Войти через Google").performClick()
        waitUntilExactlyOneExists(hasText("Скопировать ссылку"))
        onNodeWithText("https://auth.example/login").assertExists()
    }

    @Test
    fun `vault step invokes platform folder picker and displays file count`() = runComposeUiTest {
        val model = FakeMimirUiModel(fileCount = 42)
        val picker = object : PlatformFolderPicker {
            override suspend fun pickDirectory(initialPath: String?): String? = "/notes/vault"
        }
        setContent { CompositionLocalProvider(LocalFolderPicker provides picker) { SetupWizardScreen(model, onComplete = {}) } }

        onNodeWithText("Обзор…").performClick()
        waitUntilExactlyOneExists(hasText("✓ Найдено заметок: 42"))
        onNodeWithText("/notes/vault").assertIsDisplayed()
    }

    @Test
    fun `cancelling the folder picker keeps the typed path`() = runComposeUiTest {
        var configured = false
        val model = FakeMimirUiModel(onConfigureVault = { configured = true })
        val picker = object : PlatformFolderPicker {
            override suspend fun pickDirectory(initialPath: String?): String? = null
        }
        setContent { CompositionLocalProvider(LocalFolderPicker provides picker) { SetupWizardScreen(model, onComplete = {}) } }

        onNodeWithText("Введите путь к папке").performTextInput("/typed")
        onNodeWithText("Обзор…").performClick()
        waitForIdle()
        onNodeWithText("/typed").assertIsDisplayed()
        assertTrue(!configured)
    }

    @Test
    fun `browse button is hidden when the platform has no folder picker`() = runComposeUiTest {
        setContent { SetupWizardScreen(FakeMimirUiModel(), onComplete = {}) }
        onNodeWithText("Обзор…").assertDoesNotExist()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.throughPassword(model: FakeMimirUiModel, onComplete: () -> Unit) {
        setContent { SetupWizardScreen(model, onComplete = onComplete, pollIntervalMillis = 10) }
        onNodeWithText("Введите путь к папке").performTextInput("/v")
        onNodeWithText("Продолжить").performClick()
        waitUntilExactlyOneExists(hasText("Мастер-пароль шифрования"))
        onNodeWithText("Пароль").performTextInput("good")
        onNodeWithText("Разблокировать").performClick()
    }

    @Test
    fun `wizard asks to confirm the merge when both sides have notes`() = runComposeUiTest {
        var completed = false
        var syncs = 0
        val model = FakeMimirUiModel(
            loginPoll = { UiLoginPoll.Authorized(null) },
            inspection = UiVaultInspection(localFileCount = 10, remoteFileCount = 15, requiresStrategyConfirmation = true),
            onTriggerSync = { syncs++; true },
        )
        throughPassword(model) { completed = true }

        waitUntilExactlyOneExists(hasText("Двустороннее слияние с защитой от потерь"))
        assertTrue(!completed)
        assertEquals(0, syncs)
        onNodeWithText("Завершить настройку и синхронизировать").performClick()
        waitUntil { completed }
        assertEquals(1, syncs)
    }

    @Test
    fun `wizard skips the confirmation and starts sync when one side is empty`() = runComposeUiTest {
        var completed = false
        var syncs = 0
        val model = FakeMimirUiModel(
            loginPoll = { UiLoginPoll.Authorized(null) },
            inspection = UiVaultInspection(localFileCount = 0, remoteFileCount = 15, requiresStrategyConfirmation = false),
            onTriggerSync = { syncs++; true },
        )
        throughPassword(model) { completed = true }

        waitUntil { completed }
        assertEquals(1, syncs)
        onNodeWithText("Двустороннее слияние с защитой от потерь").assertDoesNotExist()
    }

    @Test
    fun testCompletesAuthorizationViaPollingWithoutUriScheme() = runComposeUiTest {
        val model = FakeMimirUiModel(
            loginMethods = both,
            browserAuthUrl = "https://auth.example/login",
            loginPoll = { UiLoginPoll.Authorized("me@x") },
        )
        toGoogleStep(model)
        waitUntilExactlyOneExists(hasText("Войти через Google"))
        onNodeWithText("Войти через Google").performClick()
        waitUntilExactlyOneExists(hasText("Мастер-пароль шифрования"))
    }

    @Test
    fun `wizard starts on access step when storage access is not granted`() = runComposeUiTest {
        val model = FakeMimirUiModel()
        val access = object : StorageAccess {
            override val granted = MutableStateFlow(false)
            override fun requestAllFiles() {}
            override fun requestNotifications() {}
        }
        setContent {
            CompositionLocalProvider(LocalStorageAccess provides access) {
                SetupWizardScreen(model, onComplete = {})
            }
        }

        onNodeWithText("Доступ к файлам").assertExists()
        onNodeWithText("Предоставить доступ").assertExists()
        onNodeWithText("Путь к хранилищу Obsidian").assertDoesNotExist()

        access.granted.value = true
        waitUntilExactlyOneExists(hasText("Путь к хранилищу Obsidian"))
    }

    @Test
    fun `wizard skips access step when storage access is already granted`() = runComposeUiTest {
        val model = FakeMimirUiModel()
        val access = object : StorageAccess {
            override val granted = MutableStateFlow(true)
            override fun requestAllFiles() {}
            override fun requestNotifications() {}
        }
        setContent {
            CompositionLocalProvider(LocalStorageAccess provides access) {
                SetupWizardScreen(model, onComplete = {})
            }
        }

        onNodeWithText("Путь к хранилищу Obsidian").assertExists()
        onNodeWithText("Доступ к файлам").assertDoesNotExist()
    }

    @Test
    fun `vault step shows discovered vaults list and selecting one configures path`() = runComposeUiTest {
        var configuredPath = ""
        val model = FakeMimirUiModel(
            fileCount = 15,
            onConfigureVault = { configuredPath = it; 15 },
        )
        val discovery = object : VaultDiscovery {
            override suspend fun findVaults(): List<String> = listOf("/sdcard/Documents/Notes")
        }
        setContent {
            CompositionLocalProvider(LocalVaultDiscovery provides discovery) {
                SetupWizardScreen(model, onComplete = {})
            }
        }

        waitUntilExactlyOneExists(hasText("Notes (/sdcard/Documents/Notes)"))
        onNodeWithText("Notes (/sdcard/Documents/Notes)").performClick()
        waitUntilExactlyOneExists(hasText("✓ Найдено заметок: 15"))
        assertEquals("/sdcard/Documents/Notes", configuredPath)
    }

    @Test
    fun `untranslatable uri error in vault step is displayed`() = runComposeUiTest {
        val model = FakeMimirUiModel()
        val picker = object : PlatformFolderPicker {
            override suspend fun pickDirectory(initialPath: String?): String? {
                throw IllegalArgumentException("Эта папка не на телефоне. Выберите папку во внутренней памяти или на карте памяти")
            }
        }
        setContent {
            CompositionLocalProvider(LocalFolderPicker provides picker) {
                SetupWizardScreen(model, onComplete = {})
            }
        }

        onNodeWithText("Обзор…").performClick()
        waitUntilExactlyOneExists(hasText("Эта папка не на телефоне. Выберите папку во внутренней памяти или на карте памяти"))
    }

    @Test
    fun `password field toggle has accessibility content description`() = runComposeUiTest {
        toGoogleStep(FakeMimirUiModel(loginPoll = { UiLoginPoll.Authorized("test@example.com") }))
        waitUntilExactlyOneExists(hasText("Мастер-пароль шифрования"))
        onNodeWithContentDescription("Показать пароль").assertIsDisplayed().performClick()
        onNodeWithContentDescription("Скрыть пароль").assertIsDisplayed().performClick()
        onNodeWithContentDescription("Показать пароль").assertIsDisplayed()
    }
}
