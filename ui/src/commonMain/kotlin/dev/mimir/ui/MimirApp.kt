package dev.mimir.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.mimir.core.StubSyncEngine
import dev.mimir.core.SyncEngine
import dev.mimir.ui.model.EngineMimirUiModel
import dev.mimir.ui.model.FakeMimirUiModel
import dev.mimir.ui.model.MimirUiModel
import dev.mimir.ui.screens.StatusScreen
import org.jetbrains.compose.ui.tooling.preview.Preview

private enum class Screen { Status }

@Composable
fun MimirApp(model: MimirUiModel) {
    MimirTheme {
        Surface(Modifier.fillMaxSize()) {
            var screen by remember { mutableStateOf(Screen.Status) }
            when (screen) {
                Screen.Status -> StatusScreen(model)
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
