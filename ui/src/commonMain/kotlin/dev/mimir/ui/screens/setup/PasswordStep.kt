package dev.mimir.ui.screens.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun PasswordStep(model: MimirUiModel, onNext: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Text("Мастер-пароль шифрования", style = MaterialTheme.typography.headlineSmall)
    MimirTextField(
        value = password,
        onValueChange = { password = it; error = null },
        label = "Пароль",
        isPassword = true,
        error = error,
        singleLine = true,
        modifier = Modifier.widthIn(min = 320.dp),
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MimirTokens.Space8),
    ) {
        Checkbox(checked = remember, onCheckedChange = { remember = it })
        Text("Запомнить на этом устройстве", style = MaterialTheme.typography.bodyMedium)
    }
    MimirButton(
        text = "Разблокировать",
        enabled = !busy && password.isNotEmpty(),
        loading = busy,
        variant = MimirButtonVariant.Primary,
        onClick = {
            busy = true
            scope.launch {
                try {
                    model.unlock(password, remember)
                    onNext()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.userMessage()
                } finally {
                    busy = false
                }
            }
        },
    )
}
