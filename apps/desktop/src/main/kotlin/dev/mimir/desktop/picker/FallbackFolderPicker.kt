package dev.mimir.desktop.picker

import dev.mimir.ui.picker.PlatformFolderPicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import java.awt.Frame
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

internal const val FOLDER_PICKER_TITLE = "Выберите папку хранилища"

sealed interface PickOutcome {
    data class Selected(val path: String) : PickOutcome
    data object Cancelled : PickOutcome
    data class Unavailable(val reason: String, val cause: Throwable? = null) : PickOutcome
}

fun interface FolderPickerCandidate : PlatformFolderPicker {
    suspend fun tryPick(initialPath: String?): PickOutcome

    override suspend fun pickDirectory(initialPath: String?): String? =
        when (val outcome = tryPick(initialPath)) {
            is PickOutcome.Selected -> outcome.path
            PickOutcome.Cancelled -> null
            is PickOutcome.Unavailable -> null
        }
}

/**
 * Цепочка обязанностей с single-flight: пробует кандидатов по порядку, останавливается на выборе или отмене
 * пользователя, на недоступности переходит к следующему. Параллельный вызов во время открытого диалога возвращает `null`.
 */
class FallbackFolderPicker(
    internal val candidates: List<FolderPickerCandidate>,
) : PlatformFolderPicker {
    constructor(vararg candidates: FolderPickerCandidate) : this(candidates.toList())

    private val mutex = Mutex()

    override suspend fun pickDirectory(initialPath: String?): String? {
        if (!mutex.tryLock()) return null
        try {
            for (candidate in candidates) {
                val outcome = try {
                    candidate.tryPick(initialPath)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    PickOutcome.Unavailable("${candidate::class.simpleName} threw exception", e)
                }
                when (outcome) {
                    is PickOutcome.Selected -> return outcome.path
                    PickOutcome.Cancelled -> return null
                    is PickOutcome.Unavailable ->
                        logger.log(Level.INFO, "Folder picker candidate unavailable: ${outcome.reason}", outcome.cause)
                }
            }
            return null
        } finally {
            mutex.unlock()
        }
    }

    private companion object {
        private val logger = Logger.getLogger(FallbackFolderPicker::class.java.name)
    }
}

/** Общее разрешение существующего начального каталога для всех десктопных пикеров. */
internal fun resolveExistingDirectory(initialPath: String?): File? {
    if (initialPath.isNullOrBlank()) return null
    val file = File(initialPath.trim())
    return when {
        file.isDirectory -> file
        file.parentFile?.isDirectory == true -> file.parentFile
        else -> null
    }
}

/** Собирает цепочку нативных диалогов выбора папки для ОС; побочные эффекты передаются параметрами. */
fun defaultFolderPicker(
    osName: String = System.getProperty("os.name").orEmpty(),
    executor: ProcessExecutor = ProcessExecutor(::runSystemProcess),
    parentFrame: () -> Frame? = { null },
): PlatformFolderPicker {
    val os = osName.lowercase()
    val awt = AwtFolderPicker(parentFrame = parentFrame)
    return when {
        os.contains("win") -> FallbackFolderPicker(WindowsFolderPicker(executor = executor), awt)
        os.contains("mac") || os.contains("darwin") -> FallbackFolderPicker(MacFolderPicker(parentFrame = parentFrame), awt)
        os.contains("linux") -> FallbackFolderPicker(PortalFolderPicker(), ZenityFolderPicker(executor = executor), awt)
        else -> awt
    }
}
