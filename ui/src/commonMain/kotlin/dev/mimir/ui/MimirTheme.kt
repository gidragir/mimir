package dev.mimir.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val ObsidianAccent = Color(0xFF705DCF)

val ObsidianDarkColors: ColorScheme = darkColorScheme(
    primary = ObsidianAccent,
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFF1E1E1E),
    onBackground = Color(0xFFDCDDDE),
    surface = Color(0xFF252525),
    onSurface = Color(0xFFDCDDDE),
    surfaceVariant = Color(0xFF2E2E2E),
    onSurfaceVariant = Color(0xFF8A8A8A),
    outline = Color(0xFF363636),
    outlineVariant = Color(0xFF363636),
)

val ObsidianLightColors: ColorScheme = lightColorScheme(
    primary = ObsidianAccent,
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF2E2E2E),
    surface = Color(0xFFF6F6F6),
    onSurface = Color(0xFF2E2E2E),
    surfaceVariant = Color(0xFFEEEEEE),
    onSurfaceVariant = Color(0xFF757575),
    outline = Color(0xFFD4D4D4),
    outlineVariant = Color(0xFFD4D4D4),
)

val ObsidianShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
)

@Composable
fun MimirTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) ObsidianDarkColors else ObsidianLightColors,
        shapes = ObsidianShapes,
        content = content,
    )
}
