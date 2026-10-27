package dev.mimir.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.mimir.core.StubSyncEngine
import dev.mimir.ui.MimirApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MimirApp(StubSyncEngine(), touchInput = true) }
    }
}
