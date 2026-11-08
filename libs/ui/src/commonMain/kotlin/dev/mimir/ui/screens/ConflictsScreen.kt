package dev.mimir.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.mimir.ui.MimirTheme
import dev.mimir.ui.MimirTokens
import dev.mimir.ui.components.MimirButton
import dev.mimir.ui.components.MimirButtonVariant
import dev.mimir.ui.components.MimirCard
import dev.mimir.ui.model.FakeMimirUiModel
import dev.mimir.ui.model.MimirUiModel
import dev.mimir.ui.model.UiConflictEntry
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Конфликтные копии: на каждую — «Оставить из облака» (копия удаляется) или «Оставить локальную» (копия заменяет оригинал). */
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
                error = e.message?.takeIf { it.isNotBlank() } ?: ConflictsTexts.RESOLVE_FAILED
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(MimirTokens.Space24),
        verticalArrangement = Arrangement.spacedBy(MimirTokens.Space12),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MimirTokens.Space16),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MimirButton(
                text = ConflictsTexts.BACK,
                variant = MimirButtonVariant.Secondary,
                onClick = onBack,
            )
            Text(ConflictsTexts.TITLE, style = MaterialTheme.typography.headlineSmall)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (conflicts.isEmpty()) {
            Text(ConflictsTexts.NO_CONFLICTS)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(MimirTokens.Space12)) {
                items(conflicts, key = { it.conflictPath }) { entry ->
                    MimirCard(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(MimirTokens.Space16),
                            verticalArrangement = Arrangement.spacedBy(MimirTokens.Space8),
                        ) {
                            Text(entry.originalPath, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${ConflictsTexts.CONFLICT_COPY_LABEL}${entry.conflictPath}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "${ConflictsTexts.CREATED_PREFIX}${entry.createdAt}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = MimirTokens.Space4),
                                horizontalArrangement = Arrangement.spacedBy(MimirTokens.Space16),
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f, fill = false),
                                    verticalArrangement = Arrangement.spacedBy(MimirTokens.Space4),
                                ) {
                                    MimirButton(
                                        text = ConflictsTexts.KEEP_CLOUD_BUTTON,
                                        variant = MimirButtonVariant.Primary,
                                        onClick = { resolve(entry, keepConflict = false) },
                                    )
                                    Text(
                                        text = ConflictsTexts.KEEP_CLOUD_HINT,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Column(
                                    modifier = Modifier.weight(1f, fill = false),
                                    verticalArrangement = Arrangement.spacedBy(MimirTokens.Space4),
                                ) {
                                    MimirButton(
                                        text = ConflictsTexts.KEEP_LOCAL_BUTTON,
                                        variant = MimirButtonVariant.Secondary,
                                        onClick = { resolve(entry, keepConflict = true) },
                                    )
                                    Text(
                                        text = ConflictsTexts.KEEP_LOCAL_HINT,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
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
