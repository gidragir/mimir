package dev.mimir.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mimir.core.BuildConfig
import dev.mimir.core.StubSyncEngine
import dev.mimir.core.SyncEngine
import dev.mimir.core.label
import dev.mimir.ui.MimirTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun StatusScreen(engine: SyncEngine) {
    val status by engine.status.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(BuildConfig.APP_NAME, style = MaterialTheme.typography.headlineMedium)
        Text(status.label())
        Button(onClick = {}, enabled = false) { Text("Войти через Google") }
    }
}

@Preview
@Composable
fun StatusScreenPreview() {
    MimirTheme {
        StatusScreen(StubSyncEngine())
    }
}
