package dev.mimir.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mimir.ui.MimirTheme
import dev.mimir.ui.ObsidianStyle
import dev.mimir.ui.ObsidianStyle.compactButton
import dev.mimir.ui.model.FakeMimirUiModel
import dev.mimir.ui.model.MimirUiModel
import dev.mimir.ui.model.UiConflictEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.ui.tooling.preview.Preview
import kotlin.time.Instant

/** Конфликтные копии: на каждую — «Оставить оригинал» (копия удаляется) или «Оставить копию» (копия заменяет оригинал). */
@Composable
fun ConflictsScreen(model: MimirUiModel, onBack: () -> Unit) {
    val conflicts by model.conflicts.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun resolve(entry: UiConflictEntry, keepConflict: Boolean) {
        scope.launch {
            try {
                model.resolveConflict(entry.originalPath, entry.conflictPath, keepConflict)
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Не удалось разрешить конфликт"
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = onBack) { Text("Назад") }
            Text("Конфликты", style = MaterialTheme.typography.headlineSmall)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (conflicts.isEmpty()) {
            Text("Конфликтов не обнаружено")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(conflicts, key = { it.conflictPath }) { entry ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        colors = ObsidianStyle.panelColors(),
                        border = ObsidianStyle.panelBorder(),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(entry.originalPath, style = MaterialTheme.typography.titleMedium)
                            Text(entry.conflictPath, style = MaterialTheme.typography.bodySmall)
                            Text("Создан: ${entry.createdAt}", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { resolve(entry, keepConflict = false) },
                                    shape = MaterialTheme.shapes.extraSmall,
                                    colors = ObsidianStyle.buttonColors(),
                                    contentPadding = ObsidianStyle.buttonPadding,
                                    modifier = Modifier.compactButton(),
                                ) { Text("Оставить оригинал") }
                                OutlinedButton(
                                    onClick = { resolve(entry, keepConflict = true) },
                                    shape = MaterialTheme.shapes.extraSmall,
                                    contentPadding = ObsidianStyle.buttonPadding,
                                    modifier = Modifier.compactButton(),
                                ) { Text("Оставить копию") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview
@Composable
fun ConflictsScreenPreview() {
    MimirTheme {
        ConflictsScreen(
            FakeMimirUiModel(conflicts = listOf(UiConflictEntry("Notes/Doc.md", "Notes/Doc (conflicted copy).md", Instant.fromEpochMilliseconds(0)))),
            onBack = {},
        )
    }
}
