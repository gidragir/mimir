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
import dev.mimir.ui.model.UiDeviceCode
import dev.mimir.ui.model.UiLoginMethod
import dev.mimir.ui.model.UiLoginPoll
import dev.mimir.ui.model.UiLoginStart
import dev.mimir.ui.platform.plainTextClipEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun DeviceCodeStep(
    model: MimirUiModel,
    pollIntervalMillis: Long,
    onNext: () -> Unit,
    onBack: (() -> Unit)?,
) {
    var code by remember { mutableStateOf<UiDeviceCode?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val clipboard = LocalClipboard.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

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
                scope.launch { clipboard.setClipEntry(plainTextClipEntry(issued.userCode)) }
                runCatching { uriHandler.openUri(issued.verificationUrl) }
            },
            variant = MimirButtonVariant.Primary,
            modifier = Modifier.fillMaxWidth().height(44.dp),
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
            modifier = Modifier.fillMaxWidth().height(44.dp),
        )
    }
    onBack?.let {
        MimirButton(
            text = "Назад",
            onClick = it,
            variant = MimirButtonVariant.Ghost,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
