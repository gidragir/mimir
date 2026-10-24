package dev.mimir.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.mimir.ui.MimirTheme
import dev.mimir.ui.model.FakeMimirUiModel
import dev.mimir.ui.model.MimirUiModel
import dev.mimir.ui.model.UiDeviceCode
import dev.mimir.ui.model.UiDevicePoll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.ui.tooling.preview.Preview

private enum class Step { Vault, Google, Password }

/** Мастер первого запуска: папка хранилища → вход в Google (device code) → пароль шифрования. */
@Composable
fun SetupWizardScreen(model: MimirUiModel, onComplete: () -> Unit, pollIntervalMillis: Long = 5_000) {
    var step by remember { mutableStateOf(Step.Vault) }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (step) {
            Step.Vault -> VaultStep(model, onNext = { step = Step.Google })
            Step.Google -> GoogleStep(model, pollIntervalMillis, onNext = { step = Step.Password })
            Step.Password -> PasswordStep(model, onComplete)
        }
    }
}

private fun Throwable.userMessage(): String = message ?: this::class.simpleName ?: "Ошибка"

@Composable
private fun VaultStep(model: MimirUiModel, onNext: () -> Unit) {
    val current by model.vaultPath.collectAsState()
    var path by remember { mutableStateOf(current.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Text("Путь к хранилищу Obsidian", style = MaterialTheme.typography.headlineSmall)
    OutlinedTextField(
        value = path,
        onValueChange = { path = it; error = null },
        label = { Text("Введите путь к папке") },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
    )
    Button(
        enabled = !busy,
        onClick = {
            if (path.isBlank()) {
                error = "Укажите путь к папке хранилища"
                return@Button
            }
            busy = true
            scope.launch {
                try {
                    model.configureVault(path.trim())
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
    ) { Text("Продолжить") }
}

@Composable
private fun GoogleStep(model: MimirUiModel, pollIntervalMillis: Long, onNext: () -> Unit) {
    var code by remember { mutableStateOf<UiDeviceCode?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(attempt) {
        error = null
        code = null
        try {
            val issued = model.startDeviceLogin()
            code = issued
            while (true) {
                delay(pollIntervalMillis)
                when (val poll = model.pollDeviceLogin(issued.deviceCode)) {
                    UiDevicePoll.Pending -> Unit
                    is UiDevicePoll.Authorized -> return@LaunchedEffect onNext()
                    is UiDevicePoll.Expired -> return@LaunchedEffect run { error = "Код истёк: ${poll.reason}" }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.userMessage()
        }
    }

    Text("Вход через Google", style = MaterialTheme.typography.headlineSmall)
    val issued = code
    if (issued != null) {
        Text("Откройте ${issued.verificationUrl} и введите код:")
        Text(issued.userCode, style = MaterialTheme.typography.displaySmall)
        Button(onClick = {
            @Suppress("DEPRECATION")
            clipboard.setText(AnnotatedString(issued.userCode))
            runCatching { uriHandler.openUri(issued.verificationUrl) }
        }) { Text("Скопировать код и открыть ссылку") }
        if (error == null) Text("Ожидание подтверждения…")
    } else if (error == null) {
        Text("Запрашиваем код…")
    }
    error?.let {
        Text(it, color = MaterialTheme.colorScheme.error)
        Button(onClick = { attempt++ }) { Text("Повторить") }
    }
}

@Composable
private fun PasswordStep(model: MimirUiModel, onComplete: () -> Unit) {
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
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = remember, onCheckedChange = { remember = it })
        Text("Запомнить на этом устройстве")
    }
    Button(
        enabled = !busy && password.isNotEmpty(),
        onClick = {
            busy = true
            scope.launch {
                try {
                    model.unlock(password, remember)
                    onComplete()
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

@Preview
@Composable
fun SetupWizardScreenPreview() {
    MimirTheme { SetupWizardScreen(FakeMimirUiModel(), onComplete = {}) }
}
