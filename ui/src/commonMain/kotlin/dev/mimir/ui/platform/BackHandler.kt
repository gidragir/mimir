package dev.mimir.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalBackHandler = staticCompositionLocalOf<(@Composable (enabled: Boolean, onBack: () -> Unit) -> Unit)?> { null }
