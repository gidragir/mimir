package dev.mimir.ui.screens.setup

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.mimir.ui.ObsidianStyle
import dev.mimir.ui.ObsidianStyle.compactButton
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
    OutlinedTextField(
        value = password,
        onValueChange = { password = it; error = null },
        label = { Text("Пароль") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        colors = ObsidianStyle.fieldColors(),
        shape = MaterialTheme.shapes.extraSmall,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = remember, onCheckedChange = { remember = it })
        Text("Запомнить на этом устройстве")
    }
    Button(
        enabled = !busy && password.isNotEmpty(),
        shape = MaterialTheme.shapes.extraSmall,
        colors = ObsidianStyle.buttonColors(),
        contentPadding = ObsidianStyle.buttonPadding,
        modifier = Modifier.compactButton(),
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
    ) { Text("Разблокировать") }
}
