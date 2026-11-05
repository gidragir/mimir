package dev.mimir.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.mimir.core.SyncStatus
import dev.mimir.ui.model.FakeMimirUiModel
import dev.mimir.ui.model.UiConflictEntry
import dev.mimir.ui.screens.ConflictsScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class ConflictsScreenTest {
    private val conflict = UiConflictEntry("Notes/Doc.md", "Notes/Doc (conflicted copy 2026-10-24-101010).md", Instant.fromEpochMilliseconds(2000))

    @Test
    fun `shows empty message when no conflicts`() = runComposeUiTest {
        setContent { ConflictsScreen(FakeMimirUiModel(conflicts = emptyList()), onBack = {}) }
        onNodeWithText("Конфликтов не обнаружено").assertExists()
    }

    @Test
    fun `resolves conflict when keep original is clicked`() = runComposeUiTest {
        var resolvedOriginal = ""
        var resolvedKeepConflict: Boolean? = null
        val model = FakeMimirUiModel(
            conflicts = listOf(conflict),
            onResolveConflict = { orig, _, keepConf ->
                resolvedOriginal = orig
                resolvedKeepConflict = keepConf
            },
        )
        setContent { ConflictsScreen(model, onBack = {}) }

        onNodeWithText("Notes/Doc.md").assertExists()
        onNodeWithText("Оставить оригинал").performClick()
        waitForIdle()

        assertEquals("Notes/Doc.md", resolvedOriginal)
        assertEquals(false, resolvedKeepConflict)
        onNodeWithText("Конфликтов не обнаружено").assertExists()
    }

    @Test
    fun `keeps the copy when keep copy is clicked`() = runComposeUiTest {
        var keepConflict: Boolean? = null
        val model = FakeMimirUiModel(conflicts = listOf(conflict), onResolveConflict = { _, _, keep -> keepConflict = keep })
        setContent { ConflictsScreen(model, onBack = {}) }

        onNodeWithText("Оставить копию").performClick()
        waitForIdle()
        assertEquals(true, keepConflict)
    }

    @Test
    fun `a failed resolution shows the error and keeps the entry`() = runComposeUiTest {
        val model = FakeMimirUiModel(
            conflicts = listOf(conflict),
            onResolveConflict = { _, _, _ -> throw IllegalStateException("disk is read-only") },
        )
        setContent { ConflictsScreen(model, onBack = {}) }

        onNodeWithText("Оставить оригинал").performClick()
        waitForIdle()
        onNodeWithText("disk is read-only").assertExists()
        onNodeWithText("Notes/Doc.md").assertExists()
    }

    @Test
    fun `back button returns`() = runComposeUiTest {
        var back = false
        setContent { ConflictsScreen(FakeMimirUiModel(), onBack = { back = true }) }
        onNodeWithText("Назад").performClick()
        assertTrue(back)
    }

    @Test
    fun `status badge opens the conflicts screen`() = runComposeUiTest {
        setContent { MimirApp(FakeMimirUiModel(status = SyncStatus.Idle, conflicts = listOf(conflict))) }
        onNodeWithText("Конфликты: 1").performClick()
        onNodeWithText("Оставить копию").assertExists()
    }
}
