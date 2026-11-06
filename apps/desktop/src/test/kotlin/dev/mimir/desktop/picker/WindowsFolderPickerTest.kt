package dev.mimir.desktop.picker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WindowsFolderPickerTest {
    private class Recorder(private val result: () -> ProcessResult) : ProcessExecutor {
        var command: List<String> = emptyList()
        var env: Map<String, String> = emptyMap()
        override suspend fun execute(command: List<String>, env: Map<String, String>): ProcessResult {
            this.command = command
            this.env = env
            return result()
        }
    }

    @Test
    fun `builds powershell sta command with utf16le encoded script and passes initial path via env`() = runTest {
        var foreground = 0
        val exec = Recorder { ProcessResult(0, "") }
        val tmp = File(System.getProperty("java.io.tmpdir"))
        WindowsFolderPicker(exec) { foreground++ }.tryPick(tmp.absolutePath)

        assertEquals(
            listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Sta", "-WindowStyle", "Hidden", "-EncodedCommand"),
            exec.command.take(7),
        )
        val script = String(Base64.getDecoder().decode(exec.command[7]), Charsets.UTF_16LE)
        assertTrue("AutoUpgradeEnabled = \$true" in script)
        assertTrue(FOLDER_PICKER_TITLE in script)
        assertTrue("\$env:MIMIR_INITIAL_PATH" in script)
        assertEquals(1, foreground)
        assertEquals(mapOf("MIMIR_INITIAL_PATH" to tmp.absolutePath), exec.env)
    }

    @Test
    fun `no initial path means empty env`() = runTest {
        val exec = Recorder { ProcessResult(0, "") }
        WindowsFolderPicker(exec) {}.tryPick(null)
        assertTrue(exec.env.isEmpty())
    }

    @Test
    fun `selected path is trimmed and keeps cyrillic and quotes`() = runTest {
        val exec = Recorder { ProcessResult(0, "C:\\Users\\Илья\\O'Neil Vault\r\n") }
        assertEquals(
            PickOutcome.Selected("C:\\Users\\Илья\\O'Neil Vault"),
            WindowsFolderPicker(exec) {}.tryPick(null),
        )
    }

    @Test
    fun `empty output with zero exit code means cancelled`() = runTest {
        assertEquals(PickOutcome.Cancelled, WindowsFolderPicker(Recorder { ProcessResult(0, "") }) {}.tryPick(null))
    }

    @Test
    fun `non-zero exit code or IOException means unavailable`() = runTest {
        assertIs<PickOutcome.Unavailable>(WindowsFolderPicker(Recorder { ProcessResult(1, "") }) {}.tryPick(null))
        assertIs<PickOutcome.Unavailable>(WindowsFolderPicker(Recorder { throw IOException("no powershell") }) {}.tryPick(null))
    }

    @Test
    fun `cancellation is rethrown`() = runTest {
        assertFailsWith<CancellationException> {
            WindowsFolderPicker(Recorder { throw CancellationException("x") }) {}.tryPick(null)
        }
    }
}
