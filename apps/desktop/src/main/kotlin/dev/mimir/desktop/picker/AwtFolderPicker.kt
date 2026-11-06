package dev.mimir.desktop.picker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

class AwtFolderPicker(private val parentFrame: () -> Frame? = { null }) : FolderPickerCandidate {
    override suspend fun tryPick(initialPath: String?): PickOutcome = withContext(Dispatchers.IO) {
        var outcome: PickOutcome = PickOutcome.Cancelled
        EventQueue.invokeAndWait {
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
            val chooser = JFileChooser().apply {
                dialogTitle = FOLDER_PICKER_TITLE
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                currentDirectory = resolveInitialDirectory(initialPath)
            }
            if (chooser.showOpenDialog(parentFrame()) == JFileChooser.APPROVE_OPTION) {
                outcome = PickOutcome.Selected(chooser.selectedFile.absolutePath)
            }
        }
        outcome
    }

    internal fun resolveInitialDirectory(initialPath: String?): File =
        resolveExistingDirectory(initialPath) ?: File(System.getProperty("user.home"))
}
