package dev.mimir.core

internal object HmacSha256 {
    private const val BLOCK = 64

    fun mac(key: ByteArray, data: ByteArray): ByteArray {
        val k = (if (key.size > BLOCK) Sha256.digest(key) else key).copyOf(BLOCK)
        val inner = ByteArray(BLOCK + data.size)
        val outer = ByteArray(BLOCK + 32)
        for (i in 0 until BLOCK) {
            inner[i] = (k[i].toInt() xor 0x36).toByte()
            outer[i] = (k[i].toInt() xor 0x5c).toByte()
        }
        data.copyInto(inner, BLOCK)
        Sha256.digest(inner).copyInto(outer, BLOCK)
        return Sha256.digest(outer)
    }
}
