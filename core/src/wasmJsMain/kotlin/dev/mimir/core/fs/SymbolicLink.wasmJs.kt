package dev.mimir.core.fs

import kotlinx.io.files.Path

internal actual fun isSymbolicLink(path: Path): Boolean = false // Локальный vault на этой платформе не используется
