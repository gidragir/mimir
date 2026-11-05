package dev.mimir.desktop.picker

import dev.mimir.ui.picker.PlatformFolderPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.io.File
import javax.swing.JFileChooser

class AwtFolderPicker : PlatformFolderPicker {
    override suspend fun pickDirectory(initialPath: String?): String? = withContext(Dispatchers.IO) {
        var picked: String? = null
        EventQueue.invokeAndWait {
            val chooser = JFileChooser().apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                currentDirectory = resolveInitialDirectory(initialPath)
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                picked = chooser.selectedFile.absolutePath
            }
        }
        picked
    }

    internal fun resolveInitialDirectory(initialPath: String?): File {
        val home = File(System.getProperty("user.home"))
        if (initialPath.isNullOrBlank()) return home
        return File(initialPath).takeIf { it.isDirectory } ?: home
    }
}
