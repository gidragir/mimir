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

val ObsidianDarkColors: ColorScheme = darkColorScheme(
    primary = MimirTokens.Accent,
    onPrimary = Color(0xFFFFFFFF),
    background = MimirTokens.DarkBase,
    onBackground = MimirTokens.DarkText,
    surface = MimirTokens.DarkSurface,
    onSurface = MimirTokens.DarkText,
    surfaceVariant = Color(0xFF2E2E2E),
    onSurfaceVariant = MimirTokens.DarkTextMuted,
    outline = MimirTokens.DarkBorder,
    outlineVariant = MimirTokens.DarkBorder,
    error = MimirTokens.StatusError,
    onError = Color(0xFFFFFFFF),
)

val ObsidianLightColors: ColorScheme = lightColorScheme(
    primary = MimirTokens.Accent,
    onPrimary = Color(0xFFFFFFFF),
    background = MimirTokens.LightBase,
    onBackground = MimirTokens.LightText,
    surface = MimirTokens.LightSurface,
    onSurface = MimirTokens.LightText,
    surfaceVariant = Color(0xFFEEEEEE),
    onSurfaceVariant = MimirTokens.LightTextMuted,
    outline = MimirTokens.LightBorder,
    outlineVariant = MimirTokens.LightBorder,
    error = MimirTokens.StatusError,
    onError = Color(0xFFFFFFFF),
)

val ObsidianShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(MimirTokens.RadiusXs),
    small = RoundedCornerShape(MimirTokens.RadiusSm),
    medium = RoundedCornerShape(MimirTokens.RadiusMd),
)

@Composable
fun MimirTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) ObsidianDarkColors else ObsidianLightColors,
        shapes = ObsidianShapes,
        content = content,
    )
}
