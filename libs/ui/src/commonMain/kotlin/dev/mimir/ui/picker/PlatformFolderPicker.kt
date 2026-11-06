package dev.mimir.ui.picker

import androidx.compose.runtime.staticCompositionLocalOf

interface PlatformFolderPicker {
    /**
     * Opens a folder chooser. Returns the chosen path, or null when cancelled
     * (the caller keeps its current path). [initialPath] is the starting directory.
     */
    suspend fun pickDirectory(initialPath: String? = null): String?
}

/** Тестовый двойник порта: возвращает [nextResult] или результат [onPick]. */
class FakeFolderPicker(
    var nextResult: String? = null,
    private val onPick: (suspend (String?) -> String?)? = null,
) : PlatformFolderPicker {
    var lastInitialPath: String? = null
        private set
    var callCount: Int = 0
        private set

    override suspend fun pickDirectory(initialPath: String?): String? {
        callCount++
        lastInitialPath = initialPath
        return if (onPick != null) onPick.invoke(initialPath) else nextResult
    }
}

val LocalFolderPicker = staticCompositionLocalOf<PlatformFolderPicker?> { null }
