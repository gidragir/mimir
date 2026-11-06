package dev.mimir.desktop.picker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.lang.reflect.InvocationTargetException

private const val DIRECTORIES_PROPERTY = "apple.awt.fileDialogForDirectories"

/** macOS: нативный `NSOpenPanel` через `java.awt.FileDialog` в режиме выбора папок, привязанный к окну Mimir. */
class MacFolderPicker(
    private val parentFrame: () -> Frame? = { null },
    private val openDialog: (Frame?, File?) -> String? = ::showFileDialog,
) : FolderPickerCandidate {
    override suspend fun tryPick(initialPath: String?): PickOutcome = try {
        withContext(Dispatchers.IO) {
            var picked: String? = null
            try {
                EventQueue.invokeAndWait {
                    val previous = System.getProperty(DIRECTORIES_PROPERTY)
                    System.setProperty(DIRECTORIES_PROPERTY, "true")
                    try {
                        picked = openDialog(parentFrame(), resolveExistingDirectory(initialPath))
                    } finally {
                        if (previous == null) System.clearProperty(DIRECTORIES_PROPERTY)
                        else System.setProperty(DIRECTORIES_PROPERTY, previous)
                    }
                }
            } catch (e: InvocationTargetException) {
                throw e.cause ?: e
            }
            picked?.let { PickOutcome.Selected(it) } ?: PickOutcome.Cancelled
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        PickOutcome.Unavailable("macOS file dialog failed", e)
    }
}

private fun showFileDialog(parent: Frame?, directory: File?): String? {
    val dialog = FileDialog(parent, FOLDER_PICKER_TITLE, FileDialog.LOAD)
    try {
        directory?.let { dialog.directory = it.absolutePath }
        dialog.isVisible = true
        return dialog.files?.firstOrNull()?.absolutePath
            ?: dialog.file?.let { File(dialog.directory.orEmpty(), it).absolutePath }
    } finally {
        dialog.dispose()
    }
}
