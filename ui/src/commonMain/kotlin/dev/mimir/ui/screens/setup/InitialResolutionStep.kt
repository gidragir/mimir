package dev.mimir.ui.screens.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import dev.mimir.ui.MimirTokens
import dev.mimir.ui.components.MimirButton
import dev.mimir.ui.components.MimirButtonVariant
import dev.mimir.ui.components.MimirCard
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
        Text(error.orEmpty(), color = MimirTokens.StatusError)
        MimirButton(
            text = "Повторить",
            onClick = { if (found == null) attempt++ else scope.launch { finish() } },
            variant = MimirButtonVariant.Primary,
        )
    } else if (found == null) {
        Text("Сравниваем папку и Google Drive…")
    } else {
        MimirCard(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(MimirTokens.Space16),
                verticalArrangement = Arrangement.spacedBy(MimirTokens.Space8),
            ) {
                Text(
                    "Обнаружено существующее хранилище в Google Drive (${found.remoteFileCount} файлов) " +
                        "и локальные заметки (${found.localFileCount} файлов).",
                    style = MaterialTheme.typography.bodyMedium,
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
        MimirButton(
            text = "Завершить настройку и синхронизировать",
            onClick = { scope.launch { finish() } },
            enabled = !busy,
            loading = busy,
            variant = MimirButtonVariant.Primary,
        )
    }
}
