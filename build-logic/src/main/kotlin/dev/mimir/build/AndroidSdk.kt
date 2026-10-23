package dev.mimir.build

import java.io.File
import java.util.Properties

object AndroidSdk {
    /** Android SDK считается доступным, если ANDROID_HOME, ANDROID_SDK_ROOT, android.sdk.path, sdk.dir или sdk.dir из local.properties указывает на существующий каталог. */
    fun isAvailable(rootDir: File, env: Map<String, String> = System.getenv()): Boolean =
        candidates(rootDir, env).any { it.isNotBlank() && File(it).isDirectory }

    private fun candidates(rootDir: File, env: Map<String, String>): List<String> =
        listOfNotNull(
            env["ANDROID_HOME"],
            env["ANDROID_SDK_ROOT"],
            System.getProperty("android.sdk.path"),
            System.getProperty("sdk.dir"),
            sdkDirFromLocalProperties(rootDir)
        )

    private fun sdkDirFromLocalProperties(rootDir: File): String? {
        val file = File(rootDir, "local.properties")
        if (!file.isFile) return null
        return Properties().apply { file.inputStream().use(::load) }.getProperty("sdk.dir")
    }
}

