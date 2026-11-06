package dev.mimir.desktop.picker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
)

fun interface ProcessExecutor {
    suspend fun execute(
        command: List<String>,
        env: Map<String, String>,
    ): ProcessResult
}

/** Запускает внешний процесс на IO; при отмене корутины убивает процесс. Нет исполняемого файла → `IOException`. */
internal suspend fun runSystemProcess(
    command: List<String>,
    env: Map<String, String> = emptyMap(),
): ProcessResult = withContext(Dispatchers.IO) {
    val process = ProcessBuilder(command)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .apply { environment().putAll(env) }
        .start()
    try {
        coroutineScope {
            // Чтение stdout не прерывается interrupt'ом, поэтому при отмене процесс уничтожается явно.
            val reader = async {
                runInterruptible {
                    val out = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
                    ProcessResult(process.waitFor(), out)
                }
            }
            try {
                reader.await()
            } catch (e: CancellationException) {
                process.destroyForcibly()
                throw e
            }
        }
    } finally {
        if (process.isAlive) process.destroyForcibly()
    }
}
