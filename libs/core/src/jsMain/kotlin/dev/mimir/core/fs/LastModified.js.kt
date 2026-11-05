package dev.mimir.core.fs

import kotlinx.io.files.Path

// Локальный vault на этой платформе не используется; mtime недоступен.
internal actual fun lastModifiedMillis(path: Path): Long = 0L
