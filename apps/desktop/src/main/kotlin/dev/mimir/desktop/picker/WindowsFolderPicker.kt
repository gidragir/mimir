package dev.mimir.desktop.picker

import dev.mimir.desktop.PlatformFocus
import kotlinx.coroutines.CancellationException
import java.util.Base64

private val POWERSHELL_SCRIPT = """
Add-Type -AssemblyName System.Windows.Forms
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
${'$'}dialog = New-Object System.Windows.Forms.FolderBrowserDialog
${'$'}dialog.Description = '$FOLDER_PICKER_TITLE'
${'$'}dialog.UseDescriptionForTitle = ${'$'}true
${'$'}dialog.ShowNewFolderButton = ${'$'}true
${'$'}dialog.AutoUpgradeEnabled = ${'$'}true
${'$'}init = ${'$'}env:MIMIR_INITIAL_PATH
if (![string]::IsNullOrWhiteSpace(${'$'}init) -and (Test-Path -LiteralPath ${'$'}init -PathType Container)) {
    ${'$'}dialog.SelectedPath = ${'$'}init
}
${'$'}top = New-Object System.Windows.Forms.Form
${'$'}top.TopMost = ${'$'}true
${'$'}top.ShowInTaskbar = ${'$'}false
${'$'}null = ${'$'}top.Handle
try {
    if (${'$'}dialog.ShowDialog(${'$'}top) -eq [System.Windows.Forms.DialogResult]::OK) {
        [Console]::Out.Write(${'$'}dialog.SelectedPath)
    }
} finally {
    ${'$'}top.Dispose()
    ${'$'}dialog.Dispose()
}
""".trimIndent()

/**
 * Windows: Common Item Dialog (проводник) через `powershell.exe -Sta`. Скрипт идёт через `-EncodedCommand` (UTF-16LE Base64),
 * начальный путь — через переменную окружения, чтобы кириллица и кавычки не ломали разбор.
 */
class WindowsFolderPicker(
    private val executor: ProcessExecutor = ProcessExecutor(::runSystemProcess),
    private val allowForeground: () -> Unit = PlatformFocus::allowForegroundTransfer,
) : FolderPickerCandidate {
    override suspend fun tryPick(initialPath: String?): PickOutcome {
        val encoded = Base64.getEncoder().encodeToString(POWERSHELL_SCRIPT.toByteArray(Charsets.UTF_16LE))
        val command = listOf(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-Sta", "-WindowStyle", "Hidden", "-EncodedCommand", encoded,
        )
        val env = resolveExistingDirectory(initialPath)?.let { mapOf("MIMIR_INITIAL_PATH" to it.absolutePath) }.orEmpty()
        return try {
            runCatching { allowForeground() }
            val result = executor.execute(command, env)
            val picked = result.stdout.trim()
            when {
                result.exitCode != 0 -> PickOutcome.Unavailable("powershell exited with ${result.exitCode}")
                picked.isEmpty() -> PickOutcome.Cancelled
                else -> PickOutcome.Selected(picked)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            PickOutcome.Unavailable("powershell failed", e)
        }
    }
}
