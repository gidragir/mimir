package dev.mimir.desktop.picker

import kotlinx.coroutines.CancellationException

/** Linux без портала: `zenity`, затем `kdialog`. Код 1 — отмена пользователем: цепочка останавливается. */
class ZenityFolderPicker(
    private val executor: ProcessExecutor = ProcessExecutor(::runSystemProcess),
) : FolderPickerCandidate {
    override suspend fun tryPick(initialPath: String?): PickOutcome {
        val dir = resolveExistingDirectory(initialPath)
        val zenity = buildList {
            add("zenity"); add("--file-selection"); add("--directory"); add("--title=$FOLDER_PICKER_TITLE")
            dir?.let { add("--filename=${it.absolutePath.trimEnd('/')}/") }
        }
        val kdialog = buildList {
            add("kdialog"); add("--title"); add(FOLDER_PICKER_TITLE); add("--getexistingdirectory")
            dir?.let { add(it.absolutePath) }
        }
        for (command in listOf(zenity, kdialog)) {
            run(command)?.let { return it }
        }
        return PickOutcome.Unavailable("Neither zenity nor kdialog is available")
    }

    /** `null` — утилита недоступна, пробуем следующую. */
    private suspend fun run(command: List<String>): PickOutcome? = try {
        val result = executor.execute(command, emptyMap())
        val picked = result.stdout.trim()
        when {
            result.exitCode == 0 && picked.isNotEmpty() -> PickOutcome.Selected(picked)
            result.exitCode == 0 || result.exitCode == 1 -> PickOutcome.Cancelled
            else -> null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }
}
