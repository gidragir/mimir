package dev.mimir.desktop.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

object WindowsProtocolRegistrar {
    const val SCHEME = "mimir"

    fun registerIfWindows(
        overrideExePath: String? = null,
        importer: (File) -> Boolean = ::importRegFile,
    ): Boolean {
        if (!isWindows()) return false
        val exePath = overrideExePath ?: resolveExecutablePath() ?: return false
        return registerProtocol(SCHEME, exePath, importer)
    }

    fun isWindows(): Boolean =
        System.getProperty("os.name", "").lowercase().contains("win")

    fun resolveExecutablePath(): String? {
        val currentCommand = ProcessHandle.current().info().command().orElse(null)
        if (!currentCommand.isNullOrBlank() && currentCommand.endsWith(".exe", ignoreCase = true)
            && !currentCommand.endsWith("java.exe", ignoreCase = true)
            && !currentCommand.endsWith("javaw.exe", ignoreCase = true)
        ) {
            return currentCommand
        }
        val localAppData = System.getenv("LOCALAPPDATA")
        if (!localAppData.isNullOrBlank()) {
            val installedExe = Paths.get(localAppData, "Mimir", "Mimir.exe").toFile()
            if (installedExe.exists()) {
                return installedExe.absolutePath
            }
        }
        return null
    }

    fun buildRegFileContent(scheme: String, exePath: String): String {
        val escapedPath = exePath.replace("\\", "\\\\").replace("\"", "\\\"")
        return """
            Windows Registry Editor Version 5.00

            [HKEY_CURRENT_USER\Software\Classes\$scheme]
            @="URL:Mimir Protocol"
            "URL Protocol"=""

            [HKEY_CURRENT_USER\Software\Classes\$scheme\DefaultIcon]
            @="\"$escapedPath\",0"

            [HKEY_CURRENT_USER\Software\Classes\$scheme\shell]

            [HKEY_CURRENT_USER\Software\Classes\$scheme\shell\open]

            [HKEY_CURRENT_USER\Software\Classes\$scheme\shell\open\command]
            @="\"$escapedPath\" \"%1\""
        """.trimIndent()
    }

    fun registerProtocol(
        scheme: String,
        exePath: String,
        importer: (File) -> Boolean = ::importRegFile,
    ): Boolean {
        val content = buildRegFileContent(scheme, exePath)
        val tempFile = try {
            Files.createTempFile("mimir-proto-", ".reg").toFile()
        } catch (_: Throwable) {
            return false
        }
        return try {
            tempFile.writeText(content, Charsets.UTF_8)
            importer(tempFile)
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    private fun importRegFile(regFile: File): Boolean = try {
        val process = ProcessBuilder("reg", "import", regFile.absolutePath)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process.waitFor() == 0
    } catch (_: Throwable) {
        false
    }
}
