package dev.mimir.core

import kotlin.time.Instant

/** Конфликтная копия файла: оригинальный путь, путь к конфликтной копии и метка создания. */
data class ConflictFileEntry(val originalPath: String, val conflictPath: String, val createdAt: Instant)
