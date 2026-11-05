package dev.mimir.core.fs

import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.Paths

internal actual fun isSymbolicLink(path: Path): Boolean = Files.isSymbolicLink(Paths.get(path.toString()))
