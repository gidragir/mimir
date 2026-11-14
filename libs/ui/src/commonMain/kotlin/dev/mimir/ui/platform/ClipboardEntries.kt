package dev.mimir.ui.platform

import androidx.compose.ui.platform.ClipEntry

internal expect fun plainTextClipEntry(text: String): ClipEntry
