package dev.mimir.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.mimir.core.SyncEngine
import dev.mimir.ui.screens.StatusScreen

@Composable
fun MimirApp(engine: SyncEngine) {
    MimirTheme {
        Surface(Modifier.fillMaxSize()) {
            StatusScreen(engine)
        }
    }
}
