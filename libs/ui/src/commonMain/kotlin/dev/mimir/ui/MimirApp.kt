package dev.mimir.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import dev.mimir.client.DaemonConnectionStatus
import dev.mimir.ui.screens.SyncTexts
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
import dev.mimir.ui.platform.LocalBackHandler
import dev.mimir.ui.screens.ConflictsScreen

import dev.mimir.ui.screens.SetupWizardScreen
import dev.mimir.ui.screens.StatusScreen
import androidx.compose.ui.tooling.preview.Preview

private enum class Screen { Status, Setup, Conflicts }

@Composable
fun MimirApp(
    model: MimirUiModel,
    touchInput: Boolean = false,
    initialDestination: Destination = Destination.Status,
) {
    MimirTheme {
      CompositionLocalProvider(LocalTouchInput provides touchInput) {
        Surface(Modifier.fillMaxSize()) {
          Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            val connection by model.connectionStatus.collectAsState()
            if (connection != DaemonConnectionStatus.Connected) ReconnectingBanner()
            var screen by remember(initialDestination) {
                mutableStateOf(
                    when (initialDestination) {
                        Destination.Status -> Screen.Status
                        Destination.Conflicts -> Screen.Conflicts
                        Destination.Login -> Screen.Setup
                    }
                )
            }
            LocalBackHandler.current?.invoke(screen != Screen.Status) {
                screen = Screen.Status
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
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
    }
}


@Composable
private fun ReconnectingBanner() {
    Text(
        SyncTexts.RECONNECTING,
        color = MaterialTheme.colorScheme.onErrorContainer,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Для точек входа без демона (Android, web): статус движка и больше ничего. */
@Composable
fun MimirApp(
    engine: SyncEngine,
    touchInput: Boolean = false,
    initialDestination: Destination = Destination.Status,
) = MimirApp(remember(engine) { EngineMimirUiModel(engine) }, touchInput, initialDestination)


@Preview
@Composable
fun MimirAppPreview() {
    MimirApp(FakeMimirUiModel())
}
