package dev.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.mimir.ui.platform.LocalStorageAccess
import dev.mimir.ui.screens.setup.AccessStep
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AccessStepTest {

    @Test
    fun `shows grant button when access missing`() = runComposeUiTest {
        val access = FakeStorageAccess(initialGranted = false)
        var advanced = false
        setContent {
            CompositionLocalProvider(LocalStorageAccess provides access) {
                AccessStep(onNext = { advanced = true })
            }
        }

        onNodeWithText("Предоставить доступ").assertExists()
        onNodeWithText("Предоставить доступ").performClick()
        assertTrue(access.requestAllFilesCalled)
        assertTrue(!advanced)
    }

    @Test
    fun `advances when access becomes granted`() = runComposeUiTest {
        val access = FakeStorageAccess(initialGranted = false)
        var advanced = false
        setContent {
            CompositionLocalProvider(LocalStorageAccess provides access) {
                AccessStep(onNext = { advanced = true })
            }
        }

        onNodeWithText("Предоставить доступ").assertExists()
        access.grantedFlow.value = true
        waitUntil { advanced }
        assertTrue(access.requestNotificationsCalled)
    }

    @Test
    fun `notification refusal does not block`() = runComposeUiTest {
        val access = FakeStorageAccess(initialGranted = false, refuseNotifications = true)
        var advanced = false
        setContent {
            CompositionLocalProvider(LocalStorageAccess provides access) {
                AccessStep(onNext = { advanced = true })
            }
        }

        access.grantedFlow.value = true
        waitUntil { advanced }
        assertTrue(access.requestNotificationsCalled)
    }
}
