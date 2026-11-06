package dev.mimir.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Single source of truth design tokens for Mimir UI.
 * Generated automatically from design/tokens.json by design/generate-tokens.mjs.
 * Do not edit directly.
 */
object MimirTokens {
    // Accent
    val Accent = Color(0xFF705DCF)
    val AccentHover = Color(0xFF826FE0)
    val AccentSubtleDark = Color(0x2E705DCF)
    val AccentSubtleLight = Color(0x1F705DCF)

    // Status
    val StatusSuccess = Color(0xFF4EAF51)
    val StatusError = Color(0xFFE5736A)
    val StatusOffline = Color(0xFF8A8A8A)

    // Dark theme surfaces & text
    val DarkBase = Color(0xFF1E1E1E)
    val DarkSurface = Color(0xFF262626)
    val DarkInput = Color(0xFF141414)
    val DarkBorder = Color(0xFF363636)
    val DarkText = Color(0xFFDCDDDE)
    val DarkTextMuted = Color(0xFF8A8A8A)

    // Light theme surfaces & text
    val LightBase = Color(0xFFFFFFFF)
    val LightSurface = Color(0xFFF8F8F8)
    val LightInput = Color(0xFFF0F0F0)
    val LightBorder = Color(0xFFD4D4D4)
    val LightText = Color(0xFF2E2E2E)
    val LightTextMuted = Color(0xFF757575)

    object Dark {
        val Base = DarkBase
        val Surface = DarkSurface
        val Input = DarkInput
        val Border = DarkBorder
        val Text = DarkText
        val TextMuted = DarkTextMuted
    }

    object Light {
        val Base = LightBase
        val Surface = LightSurface
        val Input = LightInput
        val Border = LightBorder
        val Text = LightText
        val TextMuted = LightTextMuted
    }

    // Radius
    val RadiusXs: Dp = 4.dp
    val RadiusSm: Dp = 6.dp
    val RadiusMd: Dp = 8.dp

    val Radius4: Dp = 4.dp
    val Radius6: Dp = 6.dp
    val Radius8: Dp = 8.dp

    // Spacing
    val SpaceXs: Dp = 4.dp
    val SpaceSm: Dp = 8.dp
    val SpaceMd: Dp = 12.dp
    val SpaceLg: Dp = 16.dp
    val SpaceXl: Dp = 24.dp
    val Space2xl: Dp = 32.dp
    val SpaceXxl: Dp = 32.dp

    val Space4: Dp = 4.dp
    val Space8: Dp = 8.dp
    val Space12: Dp = 12.dp
    val Space16: Dp = 16.dp
    val Space24: Dp = 24.dp
    val Space32: Dp = 32.dp
}
