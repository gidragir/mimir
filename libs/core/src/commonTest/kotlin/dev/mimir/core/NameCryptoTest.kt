package dev.mimir.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class NameCryptoTest {
    @Test
    fun `aes256 fips197 vector`() {
        // FIPS 197, приложение C.3
        val aes = Aes256("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f".hexToBytes())
        val plain = "00112233445566778899aabbccddeeff".hexToBytes()
        val cipher = aes.encryptBlock(plain)
        assertEquals("8ea2b7ca516745bfeafc49904b496089", cipher.toHex())
        assertContentEquals(plain, aes.decryptBlock(cipher))
    }

    @Test
    fun `base32hex rfc4648 vectors`() {
        // RFC 4648 §10, нижний регистр, без '='
        val vectors = listOf(
            "" to "", "f" to "co", "fo" to "cpng", "foo" to "cpnmu",
            "foob" to "cpnmuog", "fooba" to "cpnmuoj1", "foobar" to "cpnmuoj1e8",
        )
        for ((plain, encoded) in vectors) {
            assertEquals(encoded, Base32Hex.encode(plain.encodeToByteArray()))
            assertContentEquals(plain.encodeToByteArray(), Base32Hex.decode(encoded))
        }
    }

    @Test
    fun `base32hex decode rejects invalid char`() {
        assertFailsWith<CryptException> { Base32Hex.decode("cpn!u") }
        assertFailsWith<CryptException> { Base32Hex.decode("cpnmw") } // 'w' вне алфавита 0-9a-v
        assertFailsWith<CryptException> { Base32Hex.decode("c") } // недопустимая длина
    }

    @Test
    fun `base32hex decode folds only ascii case`() {
        assertContentEquals("foo".encodeToByteArray(), Base32Hex.decode("CPNMU"))
        // U+212A KELVIN SIGN и U+0130 при lowercase превращаются в 'k' и 'i' — их принимать нельзя
        assertFailsWith<CryptException> { Base32Hex.decode("\u212A0") }
        assertFailsWith<CryptException> { Base32Hex.decode("\u01300") }
    }

    @Test
    fun `eme roundtrip for 1 to 8 blocks`() {
        val eme = Eme(ByteArray(32) { (it * 3 + 1).toByte() })
        val tweak = ByteArray(16) { (it + 100).toByte() }
        for (blocks in 1..8) {
            val data = ByteArray(16 * blocks) { (it * 5).toByte() }
            val encrypted = eme.encrypt(tweak, data)
            assertEquals(data.size, encrypted.size)
            assertNotEquals(data.toHex(), encrypted.toHex())
            assertContentEquals(data, eme.decrypt(tweak, encrypted))
        }
    }

    @Test
    fun `eme rejects non multiple of 16`() {
        val eme = Eme(ByteArray(32))
        assertFailsWith<IllegalArgumentException> { eme.encrypt(ByteArray(16), ByteArray(17)) }
        assertFailsWith<IllegalArgumentException> { eme.decrypt(ByteArray(16), ByteArray(0)) }
    }
}
