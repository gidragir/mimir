package dev.mimir.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mimir.ui.MimirTheme
import dev.mimir.ui.model.FakeMimirUiModel
import dev.mimir.ui.model.MimirUiModel
import dev.mimir.ui.screens.setup.GoogleStep
import dev.mimir.ui.screens.setup.InitialResolutionStep
import dev.mimir.ui.screens.setup.PasswordStep
import dev.mimir.ui.screens.setup.VaultStep
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

@Preview
@Composable
fun SetupWizardScreenPreview() {
    MimirTheme { SetupWizardScreen(FakeMimirUiModel(), onComplete = {}) }
}
