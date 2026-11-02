package dev.mimir.ui.screens.setup

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import dev.mimir.ui.MimirTokens
import dev.mimir.ui.components.MimirButton
import dev.mimir.ui.components.MimirButtonVariant
import dev.mimir.ui.model.MimirUiModel
import dev.mimir.ui.model.UiDeviceCode
import dev.mimir.ui.model.UiLoginMethod
import dev.mimir.ui.model.UiLoginPoll
import dev.mimir.ui.model.UiLoginStart
import dev.mimir.ui.platform.LocalSystemAccountLogin
import dev.mimir.ui.platform.SystemLoginResult
import dev.mimir.ui.screens.SyncTexts
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
    // Главный способ — первый доступный в порядке SystemAccount → Browser → DeviceCode.
    val primary = available?.let { set -> UiLoginMethod.entries.firstOrNull { it in set } }
    when {
        methodsError != null -> {
            Text("Вход через Google", style = MaterialTheme.typography.headlineSmall)
            Text(methodsError.orEmpty(), color = MimirTokens.StatusError)
            MimirButton(
                text = "Повторить",
                onClick = { attempt++ },
                variant = MimirButtonVariant.Primary,
            )
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
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    if (started) {
        LaunchedEffect(attempt) {
            error = null
            openFailedUrl = null
            inBrowser = false
            var activeMethod = method
            try {
                if (activeMethod == UiLoginMethod.SystemAccount) {
                    val authResult = systemAccountLogin?.authorize() ?: SystemLoginResult.Granted
                    when (authResult) {
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
        )
        onUseCode?.let {
            MimirButton(
                text = "Войти с помощью кода на другом устройстве",
                onClick = it,
                variant = MimirButtonVariant.Ghost,
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
            )
        } else {
            Text(if (inBrowser) "Ждём подтверждения в браузере…" else "Ждём подтверждения входа…")
            openFailedUrl?.let { url ->
                Text(url)
                MimirButton(
                    text = "Скопировать ссылку",
                    onClick = {
                        @Suppress("DEPRECATION")
                        clipboard.setText(AnnotatedString(url))
                    },
                    variant = MimirButtonVariant.Secondary,
                )
            }
        }
        MimirButton(
            text = "Отмена",
            onClick = { started = false; error = null; openFailedUrl = null },
            variant = MimirButtonVariant.Ghost,
        )
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
            val start = model.startLogin(UiLoginMethod.DeviceCode)
            code = (start as? UiLoginStart.ShowDeviceCode)?.code ?: error("Unexpected sign-in start: $start")
            while (true) {
                delay(pollIntervalMillis)
                when (val poll = model.pollLogin()) {
                    UiLoginPoll.Pending -> Unit
                    is UiLoginPoll.Authorized -> return@LaunchedEffect onNext()
                    is UiLoginPoll.Failed -> return@LaunchedEffect run { error = "Код истёк: ${poll.reason}" }
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
        Text(
            issued.userCode,
            style = MaterialTheme.typography.displaySmall,
            color = MimirTokens.Accent,
        )
        MimirButton(
            text = "Скопировать код и открыть ссылку",
            onClick = {
                @Suppress("DEPRECATION")
                clipboard.setText(AnnotatedString(issued.userCode))
                runCatching { uriHandler.openUri(issued.verificationUrl) }
            },
            variant = MimirButtonVariant.Primary,
        )
        if (error == null) Text("Ожидание подтверждения…")
    } else if (error == null) {
        Text("Запрашиваем код…")
    }
    error?.let {
        Text(it, color = MimirTokens.StatusError)
        MimirButton(
            text = "Попробовать снова",
            onClick = { attempt++ },
            variant = MimirButtonVariant.Primary,
        )
    }
    onBack?.let {
        MimirButton(
            text = "Назад",
            onClick = it,
            variant = MimirButtonVariant.Ghost,
        )
    }
}
