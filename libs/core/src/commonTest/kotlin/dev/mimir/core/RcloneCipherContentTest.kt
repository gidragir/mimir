package dev.mimir.core

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RcloneCipherContentTest {
    private val ciphers get() = TestCiphers.all

    private fun plain(size: Int) = ByteArray(size) { ((it * 7 + 3) and 0xFF).toByte() }

    private fun golden(config: Int, size: Int) =
        Base64.decode(RcloneVectors.files.single { it.config == config && it.size == size }.encryptedBase64)

    private fun encrypt(cipher: RcloneCipher, data: ByteArray, nonce: ByteArray = ByteArray(24) { it.toByte() }): ByteArray {
        TestCiphers.nonce = nonce
        return cipher.encrypt(sourceOf(data)).readByteArray()
    }

    private fun decrypt(cipher: RcloneCipher, data: ByteArray): ByteArray = cipher.decrypt(sourceOf(data)).readByteArray()

    private fun Source.readInChunks(chunk: Int): ByteArray {
        val out = Buffer()
        val tmp = ByteArray(chunk)
        while (true) {
            val n = readAtMostTo(tmp, 0, chunk)
            if (n < 0) break
            out.write(tmp, 0, n)
        }
        return out.readByteArray()
    }

    private fun flip(data: ByteArray, index: Int) = data.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }

    @Test
    fun `decrypts rclone golden files`() {
        for (v in RcloneVectors.files) {
            assertContentEquals(plain(v.size), decrypt(ciphers[v.config], golden(v.config, v.size)), "size ${v.size}")
        }
    }

    @Test
    fun `encrypts byte for byte like rclone`() {
        for (v in RcloneVectors.files) {
            val expected = golden(v.config, v.size)
            val actual = encrypt(ciphers[v.config], plain(v.size), expected.copyOfRange(8, 32))
            assertContentEquals(expected, actual, "size ${v.size}")
        }
    }

    @Test
    fun `empty file is 32 byte header`() {
        val encrypted = encrypt(ciphers[0], ByteArray(0))
        assertEquals(32, encrypted.size)
        assertContentEquals("RCLONE".encodeToByteArray() + byteArrayOf(0, 0), encrypted.copyOfRange(0, 8))
        assertEquals(0, decrypt(ciphers[0], encrypted).size)
    }

    @Test
    fun `exact multiple of block size has no empty trailing block`() {
        for (size in intArrayOf(65536, 131072)) {
            val encrypted = encrypt(ciphers[0], plain(size))
            assertEquals(size + 16 * (size / 65536) + 32, encrypted.size)
            assertContentEquals(plain(size), decrypt(ciphers[0], encrypted))
        }
    }

    @Test
    fun `reading in 1 byte and odd chunks gives same result`() {
        val data = plain(65537)
        val expected = encrypt(ciphers[0], data)
        for (chunk in intArrayOf(1, 7777)) {
            TestCiphers.nonce = ByteArray(24) { it.toByte() }
            assertContentEquals(expected, ciphers[0].encrypt(sourceOf(data)).readInChunks(chunk), "encrypt chunk $chunk")
            assertContentEquals(data, ciphers[0].decrypt(sourceOf(expected)).readInChunks(chunk), "decrypt chunk $chunk")
        }
    }

    @Test
    fun `wrong password fails`() {
        val encrypted = encrypt(ciphers[0], plain(1))
        val wrong = RcloneCipher("not-the-password") { ByteArray(24) }
        assertFailsWith<CryptException> { decrypt(wrong, encrypted) }
    }

    @Test
    fun `flipped byte in header magic fails`() {
        assertFailsWith<CryptException> { decrypt(ciphers[0], flip(encrypt(ciphers[0], plain(100)), 3)) }
    }

    @Test
    fun `flipped byte in nonce fails`() {
        assertFailsWith<CryptException> { decrypt(ciphers[0], flip(encrypt(ciphers[0], plain(100)), 10)) }
    }

    @Test
    fun `flipped byte in tag fails`() {
        assertFailsWith<CryptException> { decrypt(ciphers[0], flip(encrypt(ciphers[0], plain(100)), 33)) }
    }

    @Test
    fun `flipped byte in body fails`() {
        assertFailsWith<CryptException> { decrypt(ciphers[0], flip(encrypt(ciphers[0], plain(100)), 60)) }
    }

    @Test
    fun `truncated inside block fails`() {
        val encrypted = encrypt(ciphers[0], plain(70000))
        assertFailsWith<CryptException> { decrypt(ciphers[0], encrypted.copyOf(encrypted.size - 5)) }
        assertFailsWith<CryptException> { decrypt(ciphers[0], encrypted.copyOf(32 + 10)) }
    }

    @Test
    fun `truncated header fails`() {
        val encrypted = encrypt(ciphers[0], plain(10))
        assertFailsWith<CryptException> { decrypt(ciphers[0], encrypted.copyOf(20)) }
        assertFailsWith<CryptException> { decrypt(ciphers[0], ByteArray(0)) }
    }

    @Test
    fun `decrypt is lazy about errors`() {
        // ошибка возникает при чтении, а не при создании источника
        val source = ciphers[0].decrypt(sourceOf(ByteArray(3)))
        assertFailsWith<CryptException> { source.readByteArray() }
    }

    @Test
    fun `nonce increments with carry`() {
        val base = ByteArray(24).also { it[0] = 0xFF.toByte(); it[1] = 0xFF.toByte() }
        assertContentEquals(ByteArray(24).also { it[2] = 1 }, nextNonce(base, 1))
        assertContentEquals(ByteArray(24).also { it[0] = 0xFE.toByte(); it[1] = 0xFF.toByte(); it[2] = 1 }, nextNonce(base, 0xFFFF))
        assertContentEquals(ByteArray(24), nextNonce(ByteArray(24) { 0xFF.toByte() }, 1)) // перенос уходит в никуда
        assertContentEquals(base, nextNonce(base, 0))

        val data = ByteArray(2 * 65536 + 1) { 0x42 } // три блока, первые два с одинаковым открытым текстом
        val encrypted = encrypt(ciphers[0], data, base)
        assertContentEquals(data, decrypt(ciphers[0], encrypted))
        val b0 = encrypted.copyOfRange(32, 32 + 65552)
        val b1 = encrypted.copyOfRange(32 + 65552, 32 + 2 * 65552)
        assertNotEquals(b0.toHex(), b1.toHex())
    }

    @Test
    fun `nonceSource of wrong length is rejected`() {
        val cipher = RcloneCipher("x") { ByteArray(23) }
        assertFailsWith<IllegalArgumentException> { cipher.encrypt(sourceOf(ByteArray(1))).readByteArray() }
    }

    @Test
    fun `decrypt closes upstream source when header is invalid`() {
        var closed = false
        val inner = sourceOf("NOT_RCLONE_HEADER_00000000000000".encodeToByteArray())
        val upstream = object : RawSource {
            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = inner.readAtMostTo(sink, byteCount)
            override fun close() {
                closed = true
                inner.close()
            }
        }.buffered()
        assertFailsWith<CryptException> { ciphers[0].decrypt(upstream).readByteArray() }
        assertTrue(closed)
    }

    @Test
    fun `random nonce differs between encryptions`() {
        val cipher = RcloneCipher("x")
        val a = cipher.encrypt(sourceOf(ByteArray(1))).readByteArray()
        val b = cipher.encrypt(sourceOf(ByteArray(1))).readByteArray()
        assertNotEquals(a.copyOfRange(8, 32).toHex(), b.copyOfRange(8, 32).toHex())
        assertEquals(24, randomNonce().size)
    }
}
