package dev.mimir.ui.screens.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.mimir.ui.MimirTokens
import dev.mimir.ui.components.MimirButton
import dev.mimir.ui.components.MimirButtonVariant
import dev.mimir.ui.components.MimirIcons
import dev.mimir.ui.components.MimirTextField
import dev.mimir.ui.model.MimirUiModel
import dev.mimir.ui.picker.LocalFolderPicker
import dev.mimir.ui.platform.LocalVaultDiscovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun vaultDisplayName(vaultPath: String): String =
    vaultPath.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { vaultPath }

internal fun Throwable.userMessage(): String = message ?: this::class.simpleName ?: "Ошибка"

@Composable
fun VaultStep(model: MimirUiModel, onNext: () -> Unit) {
    val current by model.vaultPath.collectAsState()
    var path by remember { mutableStateOf(current.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var noteCount by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
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

    Column(
        verticalArrangement = Arrangement.spacedBy(MimirTokens.Space4),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Путь к хранилищу Obsidian",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Укажите локальную папку с заметками Obsidian для синхронизации",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (discoveredVaults.isNotEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MimirTokens.Space8),
        ) {
            Text(
                "Обнаруженные хранилища:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (vault in discoveredVaults) {
                val label = vaultDisplayName(vault)
                MimirButton(
                    text = "$label ($vault)",
                    icon = {
                        Icon(
                            imageVector = MimirIcons.Folder,
                            contentDescription = null,
                            tint = MimirTokens.Accent,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    onClick = {
                        path = vault
                        error = null
                        scope.launch { configure(vault) }
                    },
                    enabled = !busy,
                    variant = MimirButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                )
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MimirTokens.Space8),
        verticalAlignment = Alignment.Top,
    ) {
        MimirTextField(
            value = path,
            onValueChange = { path = it; error = null; noteCount = null },
            placeholder = "Введите путь к папке",
            error = error,
            singleLine = true,
            modifier = Modifier.weight(1f).height(56.dp),
        )
        if (picker != null) {
            val pickerButtonText = if (discoveredVaults.isNotEmpty()) "Выбрать другую папку…" else "Обзор…"
            MimirButton(
                text = pickerButtonText,
                icon = {
                    Icon(
                        imageVector = MimirIcons.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                },
                onClick = {
                    if (picking) return@MimirButton
                    scope.launch {
                        picking = true
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
                        } finally {
                            picking = false
                        }
                    }
                },
                enabled = !busy && !picking,
                variant = MimirButtonVariant.Secondary,
                modifier = Modifier.height(56.dp),
            )
        }
    }

    noteCount?.let {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MimirTokens.Radius6))
                .background(MimirTokens.StatusSuccess.copy(alpha = 0.12f))
                .border(
                    BorderStroke(1.dp, MimirTokens.StatusSuccess.copy(alpha = 0.35f)),
                    RoundedCornerShape(MimirTokens.Radius6),
                )
                .padding(horizontal = MimirTokens.Space12, vertical = MimirTokens.Space8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MimirTokens.Space8),
        ) {
            Icon(
                imageVector = MimirIcons.Check,
                contentDescription = null,
                tint = MimirTokens.StatusSuccess,
                modifier = Modifier.size(16.dp),
            )
            Text(
                "✓ Найдено заметок: $it",
                color = MimirTokens.StatusSuccess,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
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
        modifier = Modifier.fillMaxWidth().height(44.dp),
    )
}
