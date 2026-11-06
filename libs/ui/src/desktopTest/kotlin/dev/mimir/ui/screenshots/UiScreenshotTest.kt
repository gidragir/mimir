package dev.mimir.ui.screenshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.mimir.core.SyncPhase
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import dev.mimir.ui.MimirTokens
import dev.mimir.ui.ObsidianDarkColors
import dev.mimir.ui.ObsidianLightColors
import dev.mimir.ui.ObsidianShapes
import dev.mimir.ui.components.MimirCard
import dev.mimir.ui.model.FakeMimirUiModel
import dev.mimir.ui.model.UiLoginMethod
import dev.mimir.ui.picker.LocalFolderPicker
import dev.mimir.ui.picker.FakeFolderPicker
import dev.mimir.ui.screens.SetupWizardScreen
import dev.mimir.ui.screens.StatusScreen
import dev.mimir.ui.screens.setup.PasswordStep
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class UiScreenshotTest {

    private val outputDir = File("../../build/reports/screenshots/desktop").apply { mkdirs() }

    private val dummyPicker = FakeFolderPicker()

    @Composable
    private fun ThemedContainer(dark: Boolean, content: @Composable () -> Unit) {
        MaterialTheme(
            colorScheme = if (dark) ObsidianDarkColors else ObsidianLightColors,
            shapes = ObsidianShapes,
        ) {
            Box(Modifier.size(width = 680.dp, height = 540.dp)) {
                content()
            }
        }
    }

    private fun saveScreenshot(fileName: String, block: @Composable () -> Unit) = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalFolderPicker provides dummyPicker) {
                block()
            }
        }
        val bitmap = onRoot().captureToImage().toAwtImage()
        val file = File(outputDir, fileName)
        ImageIO.write(bitmap, "PNG", file)
        assertTrue(file.exists() && file.length() > 0)
    }

    @Test
    fun captureSetupWizardVaultStepDark() = saveScreenshot("setup_wizard_vault_dark.png") {
        ThemedContainer(dark = true) {
            SetupWizardScreen(FakeMimirUiModel(), onComplete = {})
        }
    }

    @Test
    fun captureSetupWizardVaultStepLight() = saveScreenshot("setup_wizard_vault_light.png") {
        ThemedContainer(dark = false) {
            SetupWizardScreen(FakeMimirUiModel(), onComplete = {})
        }
    }

    @Test
    fun captureSetupWizardGoogleStepDark() = runComposeUiTest {
        val model = FakeMimirUiModel(loginMethods = setOf(UiLoginMethod.Browser, UiLoginMethod.DeviceCode))
        setContent {
            CompositionLocalProvider(LocalFolderPicker provides dummyPicker) {
                ThemedContainer(dark = true) {
                    SetupWizardScreen(model, onComplete = {})
                }
            }
        }
        onNodeWithText("Введите путь к папке").performTextInput("/home/user/vault")
        onNodeWithText("Продолжить").performClick()
        waitForIdle()

        val bitmap = onRoot().captureToImage().toAwtImage()
        val file = File(outputDir, "setup_wizard_google_dark.png")
        ImageIO.write(bitmap, "PNG", file)
        assertTrue(file.exists() && file.length() > 0)
    }

    @Test
    fun captureSetupWizardPasswordStepDark() = saveScreenshot("setup_wizard_password_dark.png") {
        ThemedContainer(dark = true) {
            MimirCard(Modifier.padding(MimirTokens.Space24)) {
                Column(Modifier.padding(MimirTokens.Space24), verticalArrangement = Arrangement.spacedBy(MimirTokens.Space16)) {
                    PasswordStep(FakeMimirUiModel(), onNext = {})
                }
            }
        }
    }

    @Test
    fun captureSetupWizardPasswordStepLight() = saveScreenshot("setup_wizard_password_light.png") {
        ThemedContainer(dark = false) {
            MimirCard(Modifier.padding(MimirTokens.Space24)) {
                Column(Modifier.padding(MimirTokens.Space24), verticalArrangement = Arrangement.spacedBy(MimirTokens.Space16)) {
                    PasswordStep(FakeMimirUiModel(), onNext = {})
                }
            }
        }
    }

    @Test
    fun captureStatusScreenIdleDark() = saveScreenshot("status_screen_idle_dark.png") {
        ThemedContainer(dark = true) {
            StatusScreen(FakeMimirUiModel(accountEmail = "user@mimir.io", vaultPath = "/home/user/Notes"))
        }
    }

    @Test
    fun captureStatusScreenIdleLight() = saveScreenshot("status_screen_idle_light.png") {
        ThemedContainer(dark = false) {
            StatusScreen(FakeMimirUiModel(accountEmail = "user@mimir.io", vaultPath = "/home/user/Notes"))
        }
    }

    @Test
    fun captureStatusScreenSyncingDark() = saveScreenshot("status_screen_syncing_dark.png") {
        ThemedContainer(dark = true) {
            StatusScreen(
                FakeMimirUiModel(
                    accountEmail = "user@mimir.io",
                    vaultPath = "/home/user/Notes",
                    status = SyncStatus.Syncing,
                    progress = MutableStateFlow(
                        SyncProgress(
                            phase = SyncPhase.Applying,
                            startedAt = Instant.fromEpochSeconds(1700000000),
                            done = 42,
                            total = 100,
                        ),
                    ),
                ),
            )
        }
    }
}
