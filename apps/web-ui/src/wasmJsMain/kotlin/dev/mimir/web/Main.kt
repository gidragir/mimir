package dev.mimir.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import dev.mimir.core.StubSyncEngine
import dev.mimir.ui.MimirApp

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport { MimirApp(StubSyncEngine()) }
}
