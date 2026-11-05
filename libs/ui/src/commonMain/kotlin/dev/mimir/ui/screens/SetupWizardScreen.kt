package dev.mimir.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.mimir.ui.MimirTheme
import dev.mimir.ui.MimirTokens
import dev.mimir.ui.components.MimirCard
import dev.mimir.ui.model.FakeMimirUiModel
import dev.mimir.ui.model.MimirUiModel
import dev.mimir.ui.platform.LocalBackHandler
import dev.mimir.ui.platform.LocalStorageAccess
import dev.mimir.ui.screens.setup.AccessStep
import dev.mimir.ui.screens.setup.GoogleStep
import dev.mimir.ui.screens.setup.InitialResolutionStep
import dev.mimir.ui.screens.setup.PasswordStep
import dev.mimir.ui.screens.setup.VaultStep
import androidx.compose.ui.tooling.preview.Preview

private enum class Step { Access, Vault, Google, Password, InitialResolution }

@Composable
private fun SetupStepIndicator(currentStep: Step) {
    if (currentStep == Step.Access) return
    val steps = listOf(Step.Vault, Step.Google, Step.Password)
    val stepNumber = when (currentStep) {
        Step.Vault -> 1
        Step.Google -> 2
        Step.Password, Step.InitialResolution -> 3
        Step.Access -> 0
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = MimirTokens.Space8),
        verticalArrangement = Arrangement.spacedBy(MimirTokens.Space8),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Шаг $stepNumber из 3",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val stepLabel = when (currentStep) {
                Step.Vault -> "Хранилище Obsidian"
                Step.Google -> "Google Drive"
                Step.Password, Step.InitialResolution -> "Шифрование"
                Step.Access -> ""
            }
            Text(
                text = stepLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MimirTokens.Accent,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MimirTokens.Space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            steps.forEach { s ->
                val isCurrent = s == currentStep || (s == Step.Password && currentStep == Step.InitialResolution)
                val isPassed = s.ordinal < currentStep.ordinal
                val barColor = when {
                    isCurrent -> MimirTokens.Accent
                    isPassed -> MimirTokens.StatusSuccess
                    else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(barColor),
                )
            }
        }
    }
}

/**
 * Мастер первого запуска: доступ к файлам (если не выдан) → папка хранилища → вход в Google → пароль шифрования → подтверждение первого слияния
 * (шаг показывается, только когда данные есть и локально, и в облаке).
 */
@Composable
fun SetupWizardScreen(model: MimirUiModel, onComplete: () -> Unit, pollIntervalMillis: Long = 5_000) {
    val storageAccess = LocalStorageAccess.current
    val initialStep = remember {
        if (storageAccess != null && !storageAccess.granted.value) {
            Step.Access
        } else {
            Step.Vault
        }
    }
    var step by remember { mutableStateOf(initialStep) }

    LocalBackHandler.current?.invoke(step != initialStep) {
        step = when (step) {
            Step.InitialResolution -> Step.Password
            Step.Password -> Step.Google
            Step.Google -> Step.Vault
            Step.Vault -> if (initialStep == Step.Access) Step.Access else Step.Vault
            Step.Access -> Step.Access
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(MimirTokens.Space24),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MimirCard(
            modifier = Modifier.widthIn(max = 540.dp).fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MimirTokens.Space24),
                verticalArrangement = Arrangement.spacedBy(MimirTokens.Space24),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SetupStepIndicator(step)
                when (step) {
                    Step.Access -> AccessStep(onNext = { step = Step.Vault })
                    Step.Vault -> VaultStep(model, onNext = { step = Step.Google })
                    Step.Google -> GoogleStep(model, pollIntervalMillis, onNext = { step = Step.Password })
                    Step.Password -> PasswordStep(model, onNext = { step = Step.InitialResolution })
                    Step.InitialResolution -> InitialResolutionStep(model, onComplete)
                }
            }
        }
    }
}

@Preview
@Composable
fun SetupWizardScreenPreview() {
    MimirTheme { SetupWizardScreen(FakeMimirUiModel(), onComplete = {}) }
}
