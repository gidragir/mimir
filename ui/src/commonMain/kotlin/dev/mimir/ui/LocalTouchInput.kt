package dev.mimir.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** `true` на сенсорных экранах (Android): интерактивные элементы получают цель касания не меньше 48 dp. */
val LocalTouchInput = staticCompositionLocalOf { false }
