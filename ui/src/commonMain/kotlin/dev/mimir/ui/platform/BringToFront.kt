package dev.mimir.ui.platform

import androidx.compose.runtime.staticCompositionLocalOf

/** Возвращает окно приложения на передний план (после входа в браузере). `null` — платформа не умеет. */
val LocalBringToFront = staticCompositionLocalOf<(() -> Unit)?> { null }
