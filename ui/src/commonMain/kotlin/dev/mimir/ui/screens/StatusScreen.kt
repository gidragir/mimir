package dev.mimir.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mimir.core.BuildConfig
import dev.mimir.core.SyncStatus
import dev.mimir.core.label
import dev.mimir.ui.MimirTheme
import dev.mimir.ui.model.FakeMimirUiModel
import dev.mimir.ui.model.MimirUiModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun StatusScreen(model: MimirUiModel, onOpenConflicts: () -> Unit = {}, onOpenSetup: () -> Unit = {}) {
    val status by model.status.collectAsState()
    val lastSync by model.lastSyncTime.collectAsState()
    val email by model.accountEmail.collectAsState()
    val conflicts by model.conflicts.collectAsState()
    val vaultPath by model.vaultPath.collectAsState()
    val isLocked by model.isLocked.collectAsState()
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(BuildConfig.APP_NAME, style = MaterialTheme.typography.headlineMedium)
        Text(status.label())
        lastSync?.let { Text("Последняя синхронизация: $it") }
        email?.let { Text("Аккаунт: $it") }
        Button(onClick = { scope.launch { model.triggerSync() } }, enabled = status == SyncStatus.Idle) {
            Text("Синхронизировать")
        }
        if (conflicts.isNotEmpty()) {
            OutlinedButton(onClick = onOpenConflicts) { Text("Конфликты: ${conflicts.size}") }
        }
        if (vaultPath == null || email == null || isLocked) {
            OutlinedButton(onClick = onOpenSetup) { Text("Настроить Mimir") }
        }
    }
}

@Preview
@Composable
fun StatusScreenPreview() {
    MimirTheme {
        StatusScreen(FakeMimirUiModel())
    }
}
