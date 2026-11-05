package dev.mimir.desktop.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsProtocolRegistrarTest {

    @Test
    fun testBuildRegFileContentCreatesValidFormat() {
        val exePath = """C:\Users\Test\AppData\Local\Mimir\Mimir.exe"""
        val content = WindowsProtocolRegistrar.buildRegFileContent("mimir", exePath)

        assertTrue(content.contains("Windows Registry Editor Version 5.00"))
        assertTrue(content.contains("[HKEY_CURRENT_USER\\Software\\Classes\\mimir]"))
        assertTrue(content.contains("@=\"URL:Mimir Protocol\""))
        assertTrue(content.contains("\"URL Protocol\"=\"\""))
        assertTrue(content.contains("[HKEY_CURRENT_USER\\Software\\Classes\\mimir\\shell\\open\\command]"))
        assertTrue(content.contains("""@="\"C:\\Users\\Test\\AppData\\Local\\Mimir\\Mimir.exe\" \"%1\"""""))
    }

    @Test
    fun testRegisterProtocolInvokesImporterAndCleansUpFile() {
        var importedFile: File? = null
        var fileContent: String? = null

        val result = WindowsProtocolRegistrar.registerProtocol(
            scheme = "mimir",
            exePath = """C:\Test\Mimir.exe""",
            importer = { file ->
                importedFile = file
                fileContent = file.readText()
                true
            },
        )

        assertTrue(result)
        assertTrue(fileContent?.contains("[HKEY_CURRENT_USER\\Software\\Classes\\mimir]") == true)
        // File must be cleaned up in finally block
        assertFalse(importedFile?.exists() == true)
    }

    @Test
    fun testRegisterIfWindowsWithOverride() {
        if (!WindowsProtocolRegistrar.isWindows()) return

        var imported = false
        val success = WindowsProtocolRegistrar.registerIfWindows(
            overrideExePath = """C:\Test\Mimir.exe""",
            importer = {
                imported = true
                true
            },
        )

        assertTrue(success)
        assertTrue(imported)
    }
}
