package dev.mimir.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.mimir.core.StubSyncEngine
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class StatusScreenTest {
    @Test
    fun `shows app title`() = runComposeUiTest {
        setContent { MimirApp(StubSyncEngine()) }
        onNodeWithText("Mimir").assertExists()
    }

    @Test
    fun `shows not connected status for stub engine`() = runComposeUiTest {
        setContent { MimirApp(StubSyncEngine()) }
        onNodeWithText("Mimir: not connected").assertExists()
    }

    @Test
    fun `sign in button is present and disabled`() = runComposeUiTest {
        setContent { MimirApp(StubSyncEngine()) }
        onNodeWithText("Войти через Google").assertIsNotEnabled()
    }
}
