package dev.mimir.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.mimir.ui.ObsidianStyle
import dev.mimir.ui.ObsidianStyle.compactButton
import dev.mimir.ui.picker.LocalFolderPicker
import dev.mimir.ui.platform.LocalBringToFront
import dev.mimir.ui.model.UiVaultInspection
import dev.mimir.ui.model.FakeMimirUiModel
import dev.mimir.ui.model.MimirUiModel
import dev.mimir.ui.model.UiBrowserPoll
import dev.mimir.ui.model.UiDeviceCode
import dev.mimir.ui.model.UiDevicePoll
import dev.mimir.ui.model.UiLoginMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.ui.tooling.preview.Preview

private enum class Step { Vault, Google, Password, InitialResolution }

/**
 * Мастер первого запуска: папка хранилища → вход в Google → пароль шифрования → подтверждение первого слияния
 * (шаг показывается, только когда данные есть и локально, и в облаке).
 */
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
            Step.Password -> PasswordStep(model, onNext = { step = Step.InitialResolution })
            Step.InitialResolution -> InitialResolutionStep(model, onComplete)
        }
    }
}

private fun Throwable.userMessage(): String = message ?: this::class.simpleName ?: "Ошибка"

@Composable
private fun VaultStep(model: MimirUiModel, onNext: () -> Unit) {
    val current by model.vaultPath.collectAsState()
    var path by remember { mutableStateOf(current.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var noteCount by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picker = LocalFolderPicker.current

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
            OutlinedButton(
                enabled = !busy,
                shape = MaterialTheme.shapes.extraSmall,
                contentPadding = ObsidianStyle.buttonPadding,
                modifier = Modifier.compactButton(),
                onClick = {
                    scope.launch {
                        val picked = picker.pickDirectory(path.trim().ifEmpty { null }) ?: return@launch
                        path = picked
                        error = null
                        configure(picked)
                    }
                },
            ) { Text("📁 Обзор…") }
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

@Composable
private fun GoogleStep(model: MimirUiModel, pollIntervalMillis: Long, onNext: () -> Unit) {
    var methods by remember { mutableStateOf<Set<UiLoginMethod>?>(null) }
    var methodsError by remember { mutableStateOf<String?>(null) }
    var showCode by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        methodsError = null
        try {
            methods = model.loginMethods()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            methodsError = e.userMessage()
        }
    }

    val available = methods
    when {
        methodsError != null -> {
            Text("Вход через Google", style = MaterialTheme.typography.headlineSmall)
            Text(methodsError.orEmpty(), color = MaterialTheme.colorScheme.error)
            Button(onClick = { attempt++ }) { Text("Повторить") }
        }
        available == null -> {
            Text("Вход через Google", style = MaterialTheme.typography.headlineSmall)
            Text("Проверяем способы входа…")
        }
        available.isEmpty() -> {
            Text("Вход через Google", style = MaterialTheme.typography.headlineSmall)
            Text("Вход в Google не настроен в этой сборке")
        }
        UiLoginMethod.Browser in available && !showCode -> BrowserLoginStep(
            model, pollIntervalMillis, onNext,
            onUseCode = if (UiLoginMethod.DeviceCode in available) ({ showCode = true }) else null,
        )
        else -> DeviceCodeStep(
            model, pollIntervalMillis, onNext,
            onBack = if (UiLoginMethod.Browser in available) ({ showCode = false }) else null,
        )
    }
}

@Composable
private fun BrowserLoginStep(model: MimirUiModel, pollIntervalMillis: Long, onNext: () -> Unit, onUseCode: (() -> Unit)?) {
    var started by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var openFailedUrl by remember { mutableStateOf<String?>(null) }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val bringToFront = LocalBringToFront.current

    if (started) {
        LaunchedEffect(attempt) {
            error = null
            openFailedUrl = null
            try {
                val url = model.startBrowserLogin()
                try {
                    uriHandler.openUri(url)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    openFailedUrl = url
                }
                while (true) {
                    delay(pollIntervalMillis)
                    when (val poll = model.pollBrowserLogin()) {
                        UiBrowserPoll.Pending -> Unit
                        is UiBrowserPoll.Authorized -> return@LaunchedEffect run { bringToFront?.invoke(); onNext() }
                        is UiBrowserPoll.Failed -> return@LaunchedEffect run { error = poll.reason }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.userMessage()
            }
        }
    }

    Text("Вход через Google", style = MaterialTheme.typography.headlineSmall)
    if (!started) {
        Button(onClick = { started = true; attempt++ }) { Text("Войти через Google") }
        onUseCode?.let { TextButton(onClick = it) { Text("Войти с помощью кода на другом устройстве") } }
    } else {
        val failure = error
        if (failure != null) {
            Text(failure, color = MaterialTheme.colorScheme.error)
            Button(onClick = { attempt++ }) { Text("Повторить") }
        } else {
            Text("Ждём подтверждения в браузере…")
            openFailedUrl?.let { url ->
                Text(url)
                Button(onClick = {
                    @Suppress("DEPRECATION")
                    clipboard.setText(AnnotatedString(url))
                }) { Text("Скопировать ссылку") }
            }
        }
        TextButton(onClick = { started = false; error = null; openFailedUrl = null }) { Text("Отмена") }
    }
}

@Composable
private fun DeviceCodeStep(model: MimirUiModel, pollIntervalMillis: Long, onNext: () -> Unit, onBack: (() -> Unit)?) {
    var code by remember { mutableStateOf<UiDeviceCode?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val bringToFront = LocalBringToFront.current

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
                    is UiDevicePoll.Authorized -> return@LaunchedEffect run { bringToFront?.invoke(); onNext() }
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
    onBack?.let { TextButton(onClick = it) { Text("Назад") } }
}

@Composable
private fun PasswordStep(model: MimirUiModel, onNext: () -> Unit) {
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

@Composable
private fun InitialResolutionStep(model: MimirUiModel, onComplete: () -> Unit) {
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
        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
        Button(
            shape = MaterialTheme.shapes.extraSmall,
            colors = ObsidianStyle.buttonColors(),
            contentPadding = ObsidianStyle.buttonPadding,
            modifier = Modifier.compactButton(),
            onClick = { if (found == null) attempt++ else scope.launch { finish() } },
        ) { Text("Повторить") }
    } else if (found == null) {
        Text("Сравниваем папку и Google Drive…")
    } else {
        Card(
            colors = ObsidianStyle.panelColors(),
            border = ObsidianStyle.panelBorder(),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Обнаружено существующее хранилище в Google Drive (${found.remoteFileCount} файлов) " +
                        "и локальные заметки (${found.localFileCount} файлов).",
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
        Button(
            enabled = !busy,
            shape = MaterialTheme.shapes.extraSmall,
            colors = ObsidianStyle.buttonColors(),
            contentPadding = ObsidianStyle.buttonPadding,
            modifier = Modifier.compactButton(),
            onClick = { scope.launch { finish() } },
        ) { Text("Завершить настройку и синхронизировать") }
    }
}

@Preview
@Composable
fun SetupWizardScreenPreview() {
    MimirTheme { SetupWizardScreen(FakeMimirUiModel(), onComplete = {}) }
}
