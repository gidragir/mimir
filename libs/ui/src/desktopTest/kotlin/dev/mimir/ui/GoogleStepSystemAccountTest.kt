package dev.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.mimir.ui.model.FakeMimirUiModel
import dev.mimir.ui.model.UiLoginMethod
import dev.mimir.ui.model.UiLoginPoll
import dev.mimir.ui.platform.LocalSystemAccountLogin
import dev.mimir.ui.platform.SystemAccountLogin
import dev.mimir.ui.platform.SystemLoginResult
import dev.mimir.ui.screens.setup.GoogleStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class GoogleStepSystemAccountTest {

    private class FakeSystemAccountLogin(
        var result: SystemLoginResult = SystemLoginResult.Granted,
    ) : SystemAccountLogin {
        var authorizeCallCount = 0

        override suspend fun authorize(): SystemLoginResult {
            authorizeCallCount++
            return result
        }
    }

    @Test
    fun `granted starts system account login and polls`() = runComposeUiTest {
        val login = FakeSystemAccountLogin(result = SystemLoginResult.Granted)
        var pollCount = 0
        var nextCalled = false

        val model = FakeMimirUiModel(
            loginMethods = setOf(UiLoginMethod.SystemAccount, UiLoginMethod.Browser),
            loginPoll = {
                pollCount++
                if (pollCount >= 2) UiLoginPoll.Authorized("user@gmail.com") else UiLoginPoll.Pending
            },
        )

        setContent {
            CompositionLocalProvider(LocalSystemAccountLogin provides login) {
                GoogleStep(model, pollIntervalMillis = 10, onNext = { nextCalled = true })
            }
        }

        onNodeWithText("Войти через Google").performClick()
        waitUntil { nextCalled }
        assertEquals(1, login.authorizeCallCount)
        assertEquals(listOf(UiLoginMethod.SystemAccount), model.startedLogins)
        assertTrue(nextCalled)
    }

    @Test
    fun `cancelled stays on step without error`() = runComposeUiTest {
        val login = FakeSystemAccountLogin(result = SystemLoginResult.Cancelled)
        var nextCalled = false

        val model = FakeMimirUiModel(
            loginMethods = setOf(UiLoginMethod.SystemAccount, UiLoginMethod.Browser),
        )

        setContent {
            CompositionLocalProvider(LocalSystemAccountLogin provides login) {
                GoogleStep(model, pollIntervalMillis = 10, onNext = { nextCalled = true })
            }
        }

        onNodeWithText("Войти через Google").performClick()
        waitForIdle()

        assertEquals(1, login.authorizeCallCount)
        assertTrue(model.startedLogins.isEmpty())
        assertFalse(nextCalled)
        onNodeWithText("Войти через Google").assertExists()
    }

    @Test
    fun `unavailable falls back to browser`() = runComposeUiTest {
        val login = FakeSystemAccountLogin(result = SystemLoginResult.Unavailable("Play services missing"))
        var nextCalled = false

        val model = FakeMimirUiModel(
            loginMethods = setOf(UiLoginMethod.SystemAccount, UiLoginMethod.Browser),
            loginPoll = { UiLoginPoll.Pending },
        )

        setContent {
            CompositionLocalProvider(LocalSystemAccountLogin provides login) {
                GoogleStep(model, pollIntervalMillis = 10, onNext = { nextCalled = true })
            }
        }

        onNodeWithText("Войти через Google").performClick()
        waitUntil { model.startedLogins.isNotEmpty() }

        assertEquals(1, login.authorizeCallCount)
        assertEquals(listOf(UiLoginMethod.Browser), model.startedLogins)
        assertFalse(nextCalled)
    }

    @Test
    fun `unavailable without browser shows error and does not start login`() = runComposeUiTest {
        val login = FakeSystemAccountLogin(result = SystemLoginResult.Unavailable("Play services missing"))
        var nextCalled = false

        val model = FakeMimirUiModel(
            loginMethods = setOf(UiLoginMethod.SystemAccount),
            loginPoll = { UiLoginPoll.Pending },
        )

        setContent {
            CompositionLocalProvider(LocalSystemAccountLogin provides login) {
                GoogleStep(model, pollIntervalMillis = 10, onNext = { nextCalled = true })
            }
        }

        onNodeWithText("Войти через Google").performClick()
        waitForIdle()

        assertEquals(1, login.authorizeCallCount)
        assertTrue(model.startedLogins.isEmpty())
        assertFalse(nextCalled)
        onNodeWithText("Вход через Google недоступен на этом устройстве").assertExists()
    }
}
