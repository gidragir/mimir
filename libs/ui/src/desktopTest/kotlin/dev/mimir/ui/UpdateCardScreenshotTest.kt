package dev.mimir.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.mimir.ui.model.UpdateInstallKind
import dev.mimir.ui.model.UpdateUiState
import dev.mimir.ui.screens.AboutUpdateCard
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/** Снимки карточки обновления в обеих темах: `build/reports/screenshots/desktop/update_card_*.png` (в git не попадают). */
@OptIn(ExperimentalTestApi::class)
class UpdateCardScreenshotTest {
    private val notes = "• Быстрее синхронизация больших хранилищ\n• Исправлена потеря фокуса окна в Wayland\n" +
        "• Новый экран входа в Google\n• Тихая проверка обновлений раз в сутки\n• Мелкие исправления\n• Ещё одна строка"

    private val states = mapOf(
        "available" to UpdateUiState.Available("0.2.0", notes, UpdateInstallKind.DownloadAndLaunch, "https://example.org/r"),
        "available_notify" to UpdateUiState.Available("0.2.0", notes, UpdateInstallKind.NotifyOnly, "https://example.org/r"),
        "downloading" to UpdateUiState.Downloading("0.2.0", 0.42f),
        "error" to UpdateUiState.Error("Не удалось скачать обновление. Проверьте подключение к интернету и повторите."),
        "uptodate" to UpdateUiState.UpToDate("0.1.0"),
        "unknown" to UpdateUiState.Unknown("0.1.0"),
        "error_sync" to UpdateUiState.Error("Идёт синхронизация — установите обновление, когда она закончится.", dev.mimir.ui.model.UpdateRetry.Install),
    )

    private fun shoot(dark: Boolean) {
        val dir = File(System.getProperty("user.dir")).resolve("../../build/reports/screenshots/desktop").canonicalFile.apply { mkdirs() }
        for ((name, state) in states) {
            runComposeUiTest {
                setContent {
                    MaterialTheme(colorScheme = if (dark) ObsidianDarkColors else ObsidianLightColors, shapes = ObsidianShapes) {
                        Box(Modifier.background(MaterialTheme.colorScheme.background).padding(24.dp).width(420.dp)) {
                            AboutUpdateCard(state, syncRunning = false, {}, {}, {}, {}, {}, currentVersion = "0.1.0")
                        }
                    }
                }
                val file = dir.resolve("update_card_${name}_${if (dark) "dark" else "light"}.png")
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
                assertTrue(file.length() > 0)
            }
        }
    }

    @Test
    fun dark() = shoot(dark = true)

    @Test
    fun light() = shoot(dark = false)
}
