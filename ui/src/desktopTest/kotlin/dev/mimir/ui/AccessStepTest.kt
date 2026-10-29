package dev.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.mimir.ui.platform.LocalStorageAccess
import dev.mimir.ui.platform.StorageAccess
import dev.mimir.ui.screens.setup.AccessStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AccessStepTest {

    private class FakeStorageAccess(
        initialGranted: Boolean = false,
        private val refuseNotifications: Boolean = false,
    ) : StorageAccess {
        val grantedFlow = MutableStateFlow(initialGranted)
        override val granted: StateFlow<Boolean> = grantedFlow
        var requestAllFilesCalled = false
        var requestNotificationsCalled = false

        override fun requestAllFiles() {
            requestAllFilesCalled = true
        }

        override fun requestNotifications() {
            requestNotificationsCalled = true
            if (refuseNotifications) {
                // Simulates notification permission denial / refusal
            }
        }
    }

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
