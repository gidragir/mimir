package dev.mimir.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScryptTest {
    private fun scrypt(p: String, s: String, n: Int, r: Int, par: Int, len: Int) =
        Scrypt.derive(p.encodeToByteArray(), s.encodeToByteArray(), n, r, par, len).toHex()

    private fun words(bytes: ByteArray) = IntArray(16) { w ->
        (0 until 4).fold(0) { acc, b -> acc or ((bytes[w * 4 + b].toInt() and 0xFF) shl (8 * b)) }
    }

    private fun bytes(words: IntArray) = ByteArray(64) { (words[it / 4] ushr (8 * (it % 4))).toByte() }

    @Test
    fun `salsa20 8 core rfc7914 section 8`() {
        val input = """
            7e 87 9a 21 4f 3e c9 86 7c a9 40 e6 41 71 8f 26
            ba ee 55 5b 8c 61 c1 b5 0d f8 46 11 6d cd 3b 1d
            ee 24 f3 19 df 9b 3d 85 14 12 1e 4b 5a c5 aa 32
            76 02 1d 29 09 c7 48 29 ed eb c6 8d b8 b8 c2 5e
        """.hexToBytes()
        val output = """
            a4 1f 85 9c 66 08 cc 99 3b 81 ca cb 02 0c ef 05
            04 4b 21 81 a2 fd 33 7d fd 7b 1c 63 96 68 2f 29
            b4 39 31 68 e3 c9 e6 bc fe 6b c5 b7 a0 6d 96 ba
            e4 24 cc 10 2c 91 74 5c 24 ad 67 3d c7 61 8f 81
        """.hexToBytes()
        assertContentEquals(output, bytes(Salsa20.core(words(input), 8)))
    }

    @Test
    fun `rfc7914 vector 1`() = assertEquals(
        "77d6576238657b203b19ca42c18a0497f16b4844e3074ae8dfdffa3fede21442" +
            "fcd0069ded0948f8326a753a0fc81f17e8d3e0fb2e0d3628cf35e20c38d18906",
        scrypt("", "", 16, 1, 1, 64),
    )

    @Test
    fun `rfc7914 vector 2`() = assertEquals(
        "fdbabe1c9d3472007856e7190d01e9fe7c6ad7cbc8237830e77376634b373162" +
            "2eaf30d92e22a3886ff109279d9830dac727afb94a83ee6d8360cbdfa2cc0640",
        scrypt("password", "NaCl", 1024, 8, 16, 64),
    )

    // В RFC 7914 §12 вектор с N=16384 — третий; четвёртый (N=1048576) по плану не берём.
    @Test
    fun `rfc7914 vector 3`() = assertEquals(
        "7023bdcb3afd7348461c06cd81fd38ebfda8fbba904f8e3ea9b543f6545da1f2" +
            "d5432955613f0fcf62d49705242a9af9e61e85dc0d651e40dfcf017b45575887",
        scrypt("pleaseletmein", "SodiumChloride", 16384, 8, 1, 64),
    )

    @Test
    fun `hsalsa20 nacl vector`() {
        // libsodium test/default/core1 и core2
        val zero16 = ByteArray(16)
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742".hexToBytes()
        val firstKey = "1b27556473e985d462cd51197a9a46c76009549eac6474f206c4ee0844f68389"
        assertEquals(firstKey, Salsa20.hsalsa20(shared, zero16).toHex())
        val noncePrefix = "69696ee955b62b73cd62bda875fc73d6".hexToBytes()
        assertEquals(
            "dc908dda0b9344a953629b733820778880f3ceb421bb61b91cbd4c3e66256ce4",
            Salsa20.hsalsa20(firstKey.hexToBytes(), noncePrefix).toHex(),
        )
    }

    @Test
    fun `rejects n not power of two`() {
        assertFailsWith<IllegalArgumentException> { Scrypt.derive(ByteArray(1), ByteArray(1), 1000, 8, 1, 32) }
    }

    @Test
    fun `derivation polls the cancellation hook and aborts when it throws`() {
        var polls = 0
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            Scrypt.derive(ByteArray(1), ByteArray(1), 16384, 8, 1, 32) {
                if (++polls >= 3) throw kotlinx.coroutines.CancellationException("cancelled")
            }
        }
        assertEquals(3, polls)
    }
}
