package dev.mimir.ui.screens.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
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
import dev.mimir.ui.MimirTokens
import dev.mimir.ui.components.MimirButton
import dev.mimir.ui.components.MimirButtonVariant
import dev.mimir.ui.components.MimirTextField
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
            verticalArrangement = Arrangement.spacedBy(MimirTokens.Space8),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for (vault in discoveredVaults) {
                val label = vault.trimEnd('/').substringAfterLast('/').ifEmpty { vault }
                MimirButton(
                    text = "📁 $label ($vault)",
                    onClick = {
                        path = vault
                        error = null
                        scope.launch { configure(vault) }
                    },
                    enabled = !busy,
                    variant = MimirButtonVariant.Secondary,
                )
            }
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(MimirTokens.Space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MimirTextField(
            value = path,
            onValueChange = { path = it; error = null; noteCount = null },
            label = "Введите путь к папке",
            error = error,
            singleLine = true,
            modifier = Modifier.widthIn(min = 320.dp),
        )
        if (picker != null) {
            val pickerButtonText = if (discoveredVaults.isNotEmpty()) "📁 Выбрать другую папку…" else "📁 Обзор…"
            MimirButton(
                text = pickerButtonText,
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
                enabled = !busy,
                variant = MimirButtonVariant.Secondary,
            )
        }
    }

    noteCount?.let {
        Text(
            "✓ Найдено заметок: $it",
            color = MimirTokens.StatusSuccess,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    MimirButton(
        text = "Продолжить",
        onClick = {
            if (path.isBlank()) {
                error = "Укажите путь к папке хранилища"
                return@MimirButton
            }
            scope.launch { if (configure(path.trim())) onNext() }
        },
        enabled = !busy,
        loading = busy,
        variant = MimirButtonVariant.Primary,
    )
}
