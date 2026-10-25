package dev.mimir.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HashTest {
    private fun sha(text: String) = Sha256.digest(text.encodeToByteArray()).toHex()

    @Test
    fun `sha256 of abc`() =
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha("abc"))

    @Test
    fun `sha256 of empty`() =
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha(""))

    @Test
    fun `sha256 across block boundary`() {
        assertEquals("b35439a4ac6f0948b6d6f9e3c6af0f5f590ce20f1bde7090ef7970686ec6738a", sha("a".repeat(56)))
        assertEquals("ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb", sha("a".repeat(64)))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
    }

    @Test
    fun `hmac rfc4231 case 1`() {
        val key = ByteArray(20) { 0x0b }
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            HmacSha256.mac(key, "Hi There".encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun `hmac with key longer than block`() {
        val key = ByteArray(131) { 0xaa.toByte() }
        val data = "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray()
        assertEquals("60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54", HmacSha256.mac(key, data).toHex())
    }

    @Test
    fun `pbkdf2 rfc7914 section 11`() {
        val dk = Pbkdf2.hmacSha256("passwd".encodeToByteArray(), "salt".encodeToByteArray(), 1, 64)
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            dk.toHex(),
        )
    }

    @Test
    fun `incremental matches one-shot across block boundaries`() {
        for (length in listOf(0, 1, 55, 56, 63, 64, 65, 1000, 200_000)) {
            val data = ByteArray(length) { (it * 31 + 7).toByte() }
            val expected = Sha256.digest(data)
            for (chunk in listOf(1, 7, 64, 65_536)) {
                val hasher = Sha256.Hasher()
                var offset = 0
                while (offset < length) {
                    val n = minOf(chunk, length - offset)
                    hasher.update(data, offset, n)
                    offset += n
                }
                assertContentEquals(expected, hasher.digest(), "length=$length chunk=$chunk")
            }
        }
    }

    @Test
    fun `hasher of nothing is the empty hash`() =
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.Hasher().digestHex())

    @Test
    fun `hasher update defaults and digestHex`() {
        val hasher = Sha256.Hasher()
        hasher.update("abc".encodeToByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hasher.digestHex())
    }

    @Test
    fun `hasher update honours offset and length`() {
        val hasher = Sha256.Hasher()
        hasher.update("xxabcyy".encodeToByteArray(), 2, 3)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hasher.digestHex())
    }

    @Test
    fun `digest after completion fails`() {
        val hasher = Sha256.Hasher()
        hasher.digest()
        assertFailsWith<IllegalStateException> { hasher.digest() }
        assertFailsWith<IllegalStateException> { hasher.digestHex() }
        assertFailsWith<IllegalStateException> { hasher.update(ByteArray(1)) }
    }
}
