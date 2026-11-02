package dev.mimir.ui.screens.setup

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.style.TextAlign
import dev.mimir.ui.components.MimirButton
import dev.mimir.ui.components.MimirButtonVariant
import dev.mimir.ui.platform.LocalStorageAccess

@Composable
fun AccessStep(onNext: () -> Unit) {
    val access = LocalStorageAccess.current
    if (access == null) {
        LaunchedEffect(Unit) { onNext() }
        return
    }

    val isGranted by access.granted.collectAsState()

    LaunchedEffect(isGranted) {
        if (isGranted) {
            try {
                access.requestNotifications()
            } catch (_: Exception) {
                // notification refusal does not block
            }
            onNext()
        }
    }

    if (!isGranted) {
        Text("Доступ к файлам", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Mimir нужен доступ ко всем файлам на устройстве, чтобы синхронизировать ваше хранилище Obsidian.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        MimirButton(
            text = "Предоставить доступ",
            onClick = { access.requestAllFiles() },
            variant = MimirButtonVariant.Primary,
        )
    }
}
