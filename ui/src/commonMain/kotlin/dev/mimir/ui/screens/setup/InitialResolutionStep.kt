package dev.mimir.ui.screens.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mimir.ui.ObsidianStyle
import dev.mimir.ui.ObsidianStyle.compactButton
import dev.mimir.ui.model.MimirUiModel
import dev.mimir.ui.model.UiVaultInspection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun InitialResolutionStep(model: MimirUiModel, onComplete: () -> Unit) {
    var inspection by remember { mutableStateOf<UiVaultInspection?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Синхронизация стартует только отсюда: демон держит её до подтверждения первого слияния.
    suspend fun finish() {
        busy = true
        try {
            model.triggerSync()
            onComplete()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.userMessage()
        } finally {
            busy = false
        }
    }

    LaunchedEffect(attempt) {
        error = null
        try {
            val found = model.inspectVault()
            if (found.requiresStrategyConfirmation) inspection = found else finish()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.userMessage()
        }
    }

    Text("Первая синхронизация", style = MaterialTheme.typography.headlineSmall)
    val found = inspection
    if (error != null) {
        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
        Button(
            shape = MaterialTheme.shapes.extraSmall,
            colors = ObsidianStyle.buttonColors(),
            contentPadding = ObsidianStyle.buttonPadding,
            modifier = Modifier.compactButton(),
            onClick = { if (found == null) attempt++ else scope.launch { finish() } },
        ) { Text("Повторить") }
    } else if (found == null) {
        Text("Сравниваем папку и Google Drive…")
    } else {
        Card(
            colors = ObsidianStyle.panelColors(),
            border = ObsidianStyle.panelBorder(),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Обнаружено существующее хранилище в Google Drive (${found.remoteFileCount} файлов) " +
                        "и локальные заметки (${found.localFileCount} файлов).",
                )
                Text("Двустороннее слияние с защитой от потерь", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Недостающие файлы скачаются из Drive, новые локальные заметки загрузятся. " +
                        "При конфликте обе версии сохранятся: вторая — копией «conflicted copy».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(
            enabled = !busy,
            shape = MaterialTheme.shapes.extraSmall,
            colors = ObsidianStyle.buttonColors(),
            contentPadding = ObsidianStyle.buttonPadding,
            modifier = Modifier.compactButton(),
            onClick = { scope.launch { finish() } },
        ) { Text("Завершить настройку и синхронизировать") }
    }
}
