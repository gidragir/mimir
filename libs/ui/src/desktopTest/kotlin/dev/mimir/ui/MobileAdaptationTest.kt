package dev.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.mimir.client.DaemonConnectionStatus
import dev.mimir.core.SyncStatus
import dev.mimir.ui.model.FakeMimirUiModel
import dev.mimir.ui.model.UiConflictEntry
import dev.mimir.ui.screens.ConflictsScreen
import dev.mimir.ui.screens.SyncTexts
import kotlin.test.Test
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class MobileAdaptationTest {
    @Test
    fun `shows reconnecting banner instead of a stale status`() = runComposeUiTest {
        val model = FakeMimirUiModel(status = SyncStatus.Idle, connectionStatus = DaemonConnectionStatus.Reconnecting)
        setContent { MimirApp(model) }
        onNodeWithText(SyncTexts.RECONNECTING).assertExists()
    }

    @Test
    fun `shows no banner while connected`() = runComposeUiTest {
        setContent { MimirApp(FakeMimirUiModel(status = SyncStatus.Idle)) }
        onNodeWithText(SyncTexts.RECONNECTING).assertDoesNotExist()
    }

    @Test
    fun `buttons are at least 48dp on touch screens`() = runComposeUiTest {
        val conflict = UiConflictEntry("a.md", "a (conflicted copy 2026-10-24-101010).md", Instant.fromEpochMilliseconds(1))
        setContent {
            CompositionLocalProvider(LocalTouchInput provides true) {
                ConflictsScreen(FakeMimirUiModel(conflicts = listOf(conflict)), onBack = {})
            }
        }
        onNode(hasText("Оставить оригинал") and hasClickAction()).assertHeightIsAtLeast(48.dp)
    }
}
