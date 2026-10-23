package dev.mimir.build

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidSdkTest {
    private val tmpRoot: File = Files.createTempDirectory("mimir-root").toFile()
    private val existingDir: File = Files.createTempDirectory("mimir-sdk").toFile()

    @Test
    fun `available when ANDROID_HOME points to existing dir`() =
        assertTrue(AndroidSdk.isAvailable(tmpRoot, mapOf("ANDROID_HOME" to existingDir.path)))

    @Test
    fun `available when ANDROID_SDK_ROOT points to existing dir`() =
        assertTrue(AndroidSdk.isAvailable(tmpRoot, mapOf("ANDROID_SDK_ROOT" to existingDir.path)))

    @Test
    fun `available when local properties has sdk dir`() {
        File(tmpRoot, "local.properties").writeText("sdk.dir=${existingDir.path}\n")
        assertTrue(AndroidSdk.isAvailable(tmpRoot, emptyMap()))
    }

    @Test
    fun `available when system property android sdk path is set`() {
        val prev = System.getProperty("android.sdk.path")
        try {
            System.setProperty("android.sdk.path", existingDir.path)
            assertTrue(AndroidSdk.isAvailable(tmpRoot, emptyMap()))
        } finally {
            if (prev == null) System.clearProperty("android.sdk.path") else System.setProperty("android.sdk.path", prev)
        }
    }

    @Test
    fun `not available when nothing configured`() =
        assertFalse(AndroidSdk.isAvailable(tmpRoot, emptyMap()))

    @Test
    fun `not available when ANDROID_HOME points to missing dir`() =
        assertFalse(AndroidSdk.isAvailable(tmpRoot, mapOf("ANDROID_HOME" to File(tmpRoot, "missing").path)))
}
