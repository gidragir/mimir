package dev.mimir.core

internal object XSalsa20 {
    /** Первые [length] байт потока XSalsa20 для [key] и 24-байтного [nonce24]. */
    fun keystream(key: ByteArray, nonce24: ByteArray, length: Int): ByteArray {
        require(key.size == 32 && nonce24.size == 24) { "bad xsalsa20 arguments" }
        val subKey = Salsa20.hsalsa20(key, nonce24.copyOfRange(0, 16))
        val tail = nonce24.copyOfRange(16, 24)
        val out = ByteArray(length)
        val state = IntArray(16)
        val work = IntArray(16)
        var counter = 0L
        var pos = 0
        while (pos + 64 <= length) {
            Salsa20.streamBlockInto(subKey, tail, counter++, state, work, out, pos)
            pos += 64
        }
        if (pos < length) {
            val rem = ByteArray(64)
            Salsa20.streamBlockInto(subKey, tail, counter, state, work, rem, 0)
            rem.copyInto(out, pos, 0, length - pos)
        }
        return out
    }
}
