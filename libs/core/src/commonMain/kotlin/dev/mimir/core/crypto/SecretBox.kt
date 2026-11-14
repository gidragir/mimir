package dev.mimir.core

/** NaCl `crypto_secretbox` (XSalsa20-Poly1305) без нулевого дополнения: результат — тег(16) ‖ шифртекст. */
internal object SecretBox {
    const val TAG_SIZE = 16
    private const val POLY_KEY_SIZE = 32

    fun seal(key: ByteArray, nonce24: ByteArray, plain: ByteArray): ByteArray {
        val ks = XSalsa20.keystream(key, nonce24, POLY_KEY_SIZE + plain.size)
        val out = ByteArray(TAG_SIZE + plain.size)
        for (i in plain.indices) out[TAG_SIZE + i] = (plain[i].toInt() xor ks[POLY_KEY_SIZE + i].toInt()).toByte()
        val tag = Poly1305.mac(ks.copyOfRange(0, POLY_KEY_SIZE), out, offset = TAG_SIZE, length = out.size - TAG_SIZE)
        tag.copyInto(out)
        return out
    }

    fun open(key: ByteArray, nonce24: ByteArray, box: ByteArray): ByteArray {
        if (box.size < TAG_SIZE) throw CryptException("encrypted block is too short")
        val bodyLen = box.size - TAG_SIZE
        val ks = XSalsa20.keystream(key, nonce24, POLY_KEY_SIZE + bodyLen)
        val expected = Poly1305.mac(ks.copyOfRange(0, POLY_KEY_SIZE), box, offset = TAG_SIZE, length = bodyLen)
        var diff = 0
        for (i in 0 until TAG_SIZE) diff = diff or (expected[i].toInt() xor box[i].toInt())
        if (diff != 0) throw CryptException("authentication failed")
        return ByteArray(bodyLen) { (box[TAG_SIZE + it].toInt() xor ks[POLY_KEY_SIZE + it].toInt()).toByte() }
    }
}
