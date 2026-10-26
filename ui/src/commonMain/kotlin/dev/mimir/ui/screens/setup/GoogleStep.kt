package dev.mimir.ui.screens.setup

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import dev.mimir.ui.model.MimirUiModel
import dev.mimir.ui.model.UiBrowserPoll
import dev.mimir.ui.model.UiDeviceCode
import dev.mimir.ui.model.UiDevicePoll
import dev.mimir.ui.model.UiLoginMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
fun GoogleStep(model: MimirUiModel, pollIntervalMillis: Long, onNext: () -> Unit) {
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
                        is UiBrowserPoll.Authorized -> return@LaunchedEffect onNext()
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
    onBack?.let { TextButton(onClick = it) { Text("Назад") } }
}
