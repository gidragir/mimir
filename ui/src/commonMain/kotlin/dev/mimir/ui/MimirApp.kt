package dev.mimir.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mimir.core.StubSyncEngine
import dev.mimir.core.SyncEngine
import dev.mimir.ui.model.EngineMimirUiModel
import dev.mimir.ui.model.FakeMimirUiModel
import dev.mimir.ui.model.MimirUiModel
import dev.mimir.ui.screens.ConflictsScreen
import dev.mimir.ui.screens.SetupWizardScreen
import dev.mimir.ui.screens.StatusScreen
import org.jetbrains.compose.ui.tooling.preview.Preview

private enum class Screen { Status, Setup, Conflicts }

@Composable
fun MimirApp(model: MimirUiModel) {
    MimirTheme {
        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            var screen by remember { mutableStateOf(Screen.Status) }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 880.dp).fillMaxWidth()) {
                    when (screen) {
                        Screen.Status -> StatusScreen(model, onOpenConflicts = { screen = Screen.Conflicts }, onOpenSetup = { screen = Screen.Setup })
                        Screen.Setup -> SetupWizardScreen(model, onComplete = { screen = Screen.Status })
                        Screen.Conflicts -> ConflictsScreen(model, onBack = { screen = Screen.Status })
                    }
                }
            }
        }
    }
}

/** Для точек входа без демона (Android, web): статус движка и больше ничего. */
@Composable
fun MimirApp(engine: SyncEngine) = MimirApp(remember(engine) { EngineMimirUiModel(engine) })

@Preview
@Composable
fun MimirAppPreview() {
    MimirApp(FakeMimirUiModel())
}
