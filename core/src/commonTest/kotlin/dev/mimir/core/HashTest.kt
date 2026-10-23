package dev.mimir.core

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
