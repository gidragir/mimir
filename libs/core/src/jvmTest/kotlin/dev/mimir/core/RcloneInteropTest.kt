package dev.mimir.core

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Живой rclone из PATH; без него тесты печатают SKIPPED и проходят. */
class RcloneInteropTest {
    private val password = "interop-test-pass"
    private val dirName = "Здоровье"
    private val fileName = "Анализы мамы.md"
    private val content = ByteArray(70_000) { ((it * 13 + 5) and 0xFF).toByte() }

    private class Result(val code: Int, val out: String)

    private fun rclone(vararg args: String): Result? = try {
        val process = ProcessBuilder("rclone", *args).redirectErrorStream(true).start()
        val out = process.inputStream.readBytes().decodeToString()
        Result(process.waitFor(), out)
    } catch (e: IOException) {
        null
    }

    private fun rcloneAvailable(): Boolean {
        val ok = rclone("version")?.code == 0
        if (!ok) println("SKIPPED: rclone not found")
        return ok
    }

    private fun cryptRemote(dir: Path): String {
        val obscured = rclone("obscure", password)!!.out.trim()
        return ":crypt,remote=$dir,password=$obscured:"
    }

    private fun withTempDirs(block: (Path, Path) -> Unit) {
        val a = Files.createTempDirectory("mimir-interop-a")
        val b = Files.createTempDirectory("mimir-interop-b")
        try {
            block(a, b)
        } finally {
            a.toFile().deleteRecursively()
            b.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rclone decrypts what mimir encrypted`() {
        if (!rcloneAvailable()) return
        val cipher = RcloneCipher(password)
        withTempDirs { encrypted, plain ->
            val target = encrypted.resolve(cipher.encryptName(dirName)).also { it.createDirectories() }
                .resolve(cipher.encryptName(fileName))
            target.writeBytes(cipher.encrypt(Buffer().also { it.write(content) }).readByteArray())

            val result = rclone("copy", cryptRemote(encrypted), plain.toString())!!
            assertEquals(0, result.code, result.out)
            assertContentEquals(content, plain.resolve(dirName).resolve(fileName).readBytes())
        }
    }

    @Test
    fun `mimir decrypts what rclone encrypted`() {
        if (!rcloneAvailable()) return
        val cipher = RcloneCipher(password)
        withTempDirs { encrypted, plain ->
            plain.resolve(dirName).createDirectories().resolve(fileName).writeBytes(content)

            val result = rclone("copy", plain.toString(), cryptRemote(encrypted))!!
            assertEquals(0, result.code, result.out)

            val dir = encrypted.resolve(cipher.encryptName(dirName))
            val stored = dir.resolve(cipher.encryptName(fileName))
            assertContentEquals(content, cipher.decrypt(Buffer().also { it.write(stored.readBytes()) }).readByteArray())
            assertEquals(fileName, cipher.decryptName(stored.fileName.toString()))
        }
    }
}
