package dev.mimir.ui.picker

import androidx.compose.runtime.staticCompositionLocalOf

interface PlatformFolderPicker {
    /**
     * Opens a folder chooser. Returns the chosen path, or null when cancelled
     * (the caller keeps its current path). [initialPath] is the starting directory.
     */
    suspend fun pickDirectory(initialPath: String? = null): String?
}

val LocalFolderPicker = staticCompositionLocalOf<PlatformFolderPicker?> { null }
