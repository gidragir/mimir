package dev.mimir.ui.screens.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mimir.ui.ObsidianStyle
import dev.mimir.ui.ObsidianStyle.compactButton
import dev.mimir.ui.model.MimirUiModel
import dev.mimir.ui.picker.LocalFolderPicker
import dev.mimir.ui.platform.LocalVaultDiscovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun Throwable.userMessage(): String = message ?: this::class.simpleName ?: "Ошибка"

@Composable
fun VaultStep(model: MimirUiModel, onNext: () -> Unit) {
    val current by model.vaultPath.collectAsState()
    var path by remember { mutableStateOf(current.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var noteCount by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picker = LocalFolderPicker.current
    val discovery = LocalVaultDiscovery.current
    var discoveredVaults by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(discovery) {
        if (discovery != null) {
            discoveredVaults = try {
                discovery.findVaults()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    suspend fun configure(target: String): Boolean {
        busy = true
        return try {
            noteCount = model.configureVault(target)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            noteCount = null
            error = e.userMessage()
            false
        } finally {
            busy = false
        }
    }

    Text("Путь к хранилищу Obsidian", style = MaterialTheme.typography.headlineSmall)

    if (discoveredVaults.isNotEmpty()) {
        Text(
            "Найденные хранилища:",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for (vault in discoveredVaults) {
                OutlinedButton(
                    enabled = !busy,
                    shape = MaterialTheme.shapes.extraSmall,
                    contentPadding = ObsidianStyle.buttonPadding,
                    modifier = Modifier.compactButton(),
                    onClick = {
                        path = vault
                        error = null
                        scope.launch { configure(vault) }
                    },
                ) {
                    val label = vault.trimEnd('/').substringAfterLast('/').ifEmpty { vault }
                    Text("📁 $label ($vault)")
                }
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = path,
            onValueChange = { path = it; error = null; noteCount = null },
            label = { Text("Введите путь к папке") },
            singleLine = true,
            isError = error != null,
            colors = ObsidianStyle.fieldColors(),
            shape = MaterialTheme.shapes.extraSmall,
            modifier = Modifier.widthIn(min = 320.dp),
        )
        if (picker != null) {
            val pickerButtonText = if (discoveredVaults.isNotEmpty()) "📁 Выбрать другую папку…" else "📁 Обзор…"
            OutlinedButton(
                enabled = !busy,
                shape = MaterialTheme.shapes.extraSmall,
                contentPadding = ObsidianStyle.buttonPadding,
                modifier = Modifier.compactButton(),
                onClick = {
                    scope.launch {
                        try {
                            val picked = picker.pickDirectory(path.trim().ifEmpty { null }) ?: return@launch
                            path = picked
                            error = null
                            configure(picked)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            noteCount = null
                            error = e.userMessage()
                        }
                    }
                },
            ) { Text(pickerButtonText) }
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    noteCount?.let { Text("✓ Найдено заметок: $it", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
    Button(
        enabled = !busy,
        shape = MaterialTheme.shapes.extraSmall,
        colors = ObsidianStyle.buttonColors(),
        contentPadding = ObsidianStyle.buttonPadding,
        modifier = Modifier.compactButton(),
        onClick = {
            if (path.isBlank()) {
                error = "Укажите путь к папке хранилища"
                return@Button
            }
            scope.launch { if (configure(path.trim())) onNext() }
        },
    ) { Text("Продолжить") }
}
