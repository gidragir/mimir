package dev.mimir.core

internal object XSalsa20 {
    /** Первые [length] байт потока XSalsa20 для [key] и 24-байтного [nonce24]. */
    fun keystream(key: ByteArray, nonce24: ByteArray, length: Int): ByteArray {
        require(key.size == 32 && nonce24.size == 24) { "bad xsalsa20 arguments" }
        val subKey = Salsa20.hsalsa20(key, nonce24.copyOfRange(0, 16))
        val tail = nonce24.copyOfRange(16, 24)
        val out = ByteArray(length)
        var counter = 0L
        var pos = 0
        while (pos < length) {
            val block = Salsa20.streamBlock(subKey, tail, counter++)
            val n = minOf(64, length - pos)
            block.copyInto(out, pos, 0, n)
            pos += n
        }
        return out
    }
}
