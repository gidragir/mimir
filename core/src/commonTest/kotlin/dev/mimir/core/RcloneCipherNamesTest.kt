package dev.mimir.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RcloneCipherNamesTest {
    private val ciphers get() = TestCiphers.all
    private val cipher get() = ciphers[0]

    @Test
    fun `names match rclone golden`() {
        for (v in RcloneVectors.names) {
            val c = ciphers[v.config]
            assertEquals(v.encrypted, c.encryptName(v.plain), "encrypt ${v.plain.take(20)}")
            assertEquals(v.plain, c.decryptName(v.encrypted), "decrypt ${v.plain.take(20)}")
        }
    }

    @Test
    fun `empty segment stays empty`() {
        assertEquals("", cipher.encryptName(""))
        assertEquals("", cipher.decryptName(""))
    }

    @Test
    fun `long name roundtrips`() {
        val name = "ы".repeat(150) // 300 байт UTF-8
        assertEquals(name, cipher.decryptName(cipher.encryptName(name)))
        val ascii = "x".repeat(200)
        assertEquals(ascii, cipher.decryptName(cipher.encryptName(ascii)))
    }

    @Test
    fun `emoji and cyrillic roundtrip`() {
        for (name in listOf("Здоровье", "заметка 😀.md", "a b  c", "日本語")) {
            val encrypted = cipher.encryptName(name)
            assertTrue(encrypted.all { it in '0'..'9' || it in 'a'..'v' }, encrypted)
            assertEquals(name, cipher.decryptName(encrypted))
        }
    }

    @Test
    fun `decrypt rejects non base32`() {
        assertFailsWith<CryptException> { cipher.decryptName("не-base32!") }
        assertFailsWith<CryptException> { cipher.decryptName("zzzzzzzzzzzzzzzzzzzzzzzzzz") }
    }

    @Test
    fun `decrypt rejects length not multiple of 16 bytes`() {
        // 5 байт -> 8 символов base32; не кратно 16 байтам
        assertFailsWith<CryptException> { cipher.decryptName("cpnmuoj1") }
        assertFailsWith<CryptException> { cipher.decryptName("c0") }
    }

    @Test
    fun `decrypt rejects bad padding`() {
        val encrypted = cipher.encryptName("a.md")
        val mid = encrypted.length / 2
        val flipped = encrypted.substring(0, mid) + (if (encrypted[mid] == '0') '1' else '0') + encrypted.substring(mid + 1)
        assertFailsWith<CryptException> { cipher.decryptName(flipped) }
    }

    @Test
    fun `empty password rejected`() {
        assertFailsWith<IllegalArgumentException> { RcloneCipher("") { ByteArray(24) } }
    }

    @Test
    fun `password2 changes names`() {
        assertNotEquals(ciphers[0].encryptName("a.md"), ciphers[1].encryptName("a.md"))
    }

    @Test
    fun `toString hides keys`() {
        assertEquals("RcloneCipher", cipher.toString())
        assertFalse(cipher.toString().contains(RcloneVectors.configs[0].password))
    }
}
