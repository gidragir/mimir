package dev.mimir.ui.screens.setup

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.mimir.ui.MimirTokens
import dev.mimir.ui.components.MimirButton
import dev.mimir.ui.components.MimirButtonVariant
import dev.mimir.ui.model.MimirUiModel
import dev.mimir.ui.model.UiLoginMethod
import dev.mimir.ui.model.UiLoginPoll
import dev.mimir.ui.model.UiLoginStart
import dev.mimir.ui.platform.LocalSystemAccountLogin
import dev.mimir.ui.platform.SystemLoginResult
import dev.mimir.ui.platform.plainTextClipEntry
import dev.mimir.ui.screens.SyncTexts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    // Главный способ — первый доступный в порядке SystemAccount → Browser → DeviceCode.
    val primary = available?.let { set -> UiLoginMethod.entries.firstOrNull { it in set } }
    when {
        methodsError != null -> {
            Text("Вход через Google", style = MaterialTheme.typography.headlineSmall)
            Text(methodsError.orEmpty(), color = MimirTokens.StatusError)
            MimirButton(text = "Повторить", onClick = { attempt++ }, variant = MimirButtonVariant.Primary)
        }
        available == null -> {
            Text("Вход через Google", style = MaterialTheme.typography.headlineSmall)
            Text("Проверяем способы входа…")
        }
        primary == null -> {
            Text("Вход через Google", style = MaterialTheme.typography.headlineSmall)
            Text("Вход в Google не настроен в этой сборке")
        }
        primary != UiLoginMethod.DeviceCode && !showCode -> InteractiveLoginStep(
            model, primary, available, pollIntervalMillis, onNext,
            onUseCode = if (UiLoginMethod.DeviceCode in available) ({ showCode = true }) else null,
        )
        else -> DeviceCodeStep(
            model, pollIntervalMillis, onNext,
            onBack = if (primary != UiLoginMethod.DeviceCode) ({ showCode = false }) else null,
        )
    }
}

/** Вход по кнопке: ссылка в браузере ([UiLoginStart.OpenUrl]) или системное окно ([UiLoginStart.Started]), затем опрос. */
@Composable
private fun InteractiveLoginStep(
    model: MimirUiModel,
    method: UiLoginMethod,
    availableMethods: Set<UiLoginMethod>,
    pollIntervalMillis: Long,
    onNext: () -> Unit,
    onUseCode: (() -> Unit)?,
) {
    var started by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var inBrowser by remember { mutableStateOf(false) }
    var openFailedUrl by remember { mutableStateOf<String?>(null) }
    val systemAccountLogin = LocalSystemAccountLogin.current
    val clipboard = LocalClipboard.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    if (started) {
        LaunchedEffect(attempt) {
            error = null
            openFailedUrl = null
            inBrowser = false
            var activeMethod = method
            try {
                if (activeMethod == UiLoginMethod.SystemAccount) {
                    when (systemAccountLogin?.authorize() ?: SystemLoginResult.Granted) {
                        SystemLoginResult.Granted -> Unit
                        SystemLoginResult.Cancelled -> {
                            started = false
                            return@LaunchedEffect
                        }
                        is SystemLoginResult.Unavailable -> {
                            if (UiLoginMethod.Browser in availableMethods) {
                                activeMethod = UiLoginMethod.Browser
                            } else {
                                error = SyncTexts.GOOGLE_UNAVAILABLE
                                return@LaunchedEffect
                            }
                        }
                    }
                }
                when (val start = model.startLogin(activeMethod)) {
                    is UiLoginStart.OpenUrl -> {
                        inBrowser = true
                        try {
                            uriHandler.openUri(start.url)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            openFailedUrl = start.url
                        }
                    }
                    UiLoginStart.Started -> Unit
                    is UiLoginStart.ShowDeviceCode -> error("Unexpected device code for $activeMethod")
                }
                while (true) {
                    delay(pollIntervalMillis)
                    when (val poll = model.pollLogin()) {
                        UiLoginPoll.Pending -> Unit
                        is UiLoginPoll.Authorized -> return@LaunchedEffect onNext()
                        is UiLoginPoll.Failed -> return@LaunchedEffect run { error = poll.reason }
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
        MimirButton(
            text = "Войти через Google",
            onClick = { started = true; attempt++ },
            variant = MimirButtonVariant.Primary,
            modifier = Modifier.fillMaxWidth().height(44.dp),
        )
        onUseCode?.let {
            MimirButton(
                text = "Войти с помощью кода на другом устройстве",
                onClick = it,
                variant = MimirButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        val failure = error
        if (failure != null) {
            Text(failure, color = MimirTokens.StatusError)
            MimirButton(
                text = "Попробовать снова",
                onClick = { attempt++ },
                variant = MimirButtonVariant.Primary,
                modifier = Modifier.fillMaxWidth().height(44.dp),
            )
        } else {
            Text(if (inBrowser) "Ждём подтверждения в браузере…" else "Ждём подтверждения входа…")
            openFailedUrl?.let { url ->
                Text(url)
                MimirButton(
                    text = "Скопировать ссылку",
                    onClick = { scope.launch { clipboard.setClipEntry(plainTextClipEntry(url)) } },
                    variant = MimirButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                )
            }
        }
        MimirButton(
            text = "Отмена",
            onClick = { started = false; error = null; openFailedUrl = null },
            variant = MimirButtonVariant.Ghost,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
