package dev.mimir.core.fs

import kotlinx.io.files.Path
import java.io.File

internal actual fun lastModifiedMillis(path: Path): Long = File(path.toString()).lastModified()
