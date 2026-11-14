package dev.mimir.core

internal object Salsa20 {
    private val SIGMA = "expand 32-byte k".encodeToByteArray()

    /** Salsa20/[rounds]: слова после раундов плюс вход (как в Salsa20 core). */
    fun core(input: IntArray, rounds: Int): IntArray {
        require(input.size == 16 && rounds % 2 == 0) { "bad salsa20 core arguments" }
        val x = input.copyOf()
        coreInPlace(x, IntArray(16), rounds)
        return x
    }

    /** Salsa20/[rounds] на месте: результат записывается обратно в [state]; [work] — рабочий буфер из 16 Int. */
    fun coreInPlace(state: IntArray, work: IntArray, rounds: Int = 20) {
        require(state.size == 16 && work.size == 16 && rounds % 2 == 0) { "bad salsa20 core arguments" }
        state.copyInto(work)
        rounds(work, rounds)
        for (i in 0 until 16) state[i] += work[i]
    }

    /** HSalsa20: 32-байтный ключ из ключа и 16-байтного nonce (первая половина XSalsa20). */
    fun hsalsa20(key: ByteArray, nonce16: ByteArray): ByteArray {
        require(key.size == 32 && nonce16.size == 16) { "bad hsalsa20 arguments" }
        val x = IntArray(16)
        for (i in 0 until 4) x[i * 5] = le32(SIGMA, i * 4)
        for (i in 0 until 4) {
            x[1 + i] = le32(key, i * 4)
            x[11 + i] = le32(key, 16 + i * 4)
            x[6 + i] = le32(nonce16, i * 4)
        }
        rounds(x, 20)
        val out = ByteArray(32)
        val picks = intArrayOf(0, 5, 10, 15, 6, 7, 8, 9)
        for (i in 0 until 8) putLe32(out, i * 4, x[picks[i]])
        return out
    }

    /** 64-байтный блок Salsa20-потока для 16-байтного (nonce8 ‖ counter8) хвоста. */
    fun streamBlock(key: ByteArray, nonce8: ByteArray, counter: Long): ByteArray {
        val bytes = ByteArray(64)
        streamBlockInto(key, nonce8, counter, IntArray(16), IntArray(16), bytes, 0)
        return bytes
    }

    /** Записывает 64-байтный блок Salsa20-потока в [out] с позиции [outOffset] без аллокаций. */
    fun streamBlockInto(
        key: ByteArray,
        nonce8: ByteArray,
        counter: Long,
        state: IntArray,
        work: IntArray,
        out: ByteArray,
        outOffset: Int,
    ) {
        for (i in 0 until 4) state[i * 5] = le32(SIGMA, i * 4)
        for (i in 0 until 4) {
            state[1 + i] = le32(key, i * 4)
            state[11 + i] = le32(key, 16 + i * 4)
        }
        state[6] = le32(nonce8, 0)
        state[7] = le32(nonce8, 4)
        state[8] = counter.toInt()
        state[9] = (counter ushr 32).toInt()
        coreInPlace(state, work, 20)
        for (i in 0 until 16) putLe32(out, outOffset + i * 4, state[i])
    }

    private fun rounds(x: IntArray, rounds: Int) {
        repeat(rounds / 2) {
            // столбцы
            qr(x, 0, 4, 8, 12); qr(x, 5, 9, 13, 1); qr(x, 10, 14, 2, 6); qr(x, 15, 3, 7, 11)
            // строки
            qr(x, 0, 1, 2, 3); qr(x, 5, 6, 7, 4); qr(x, 10, 11, 8, 9); qr(x, 15, 12, 13, 14)
        }
    }

    private fun qr(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[b] = x[b] xor (x[a] + x[d]).rotateLeft(7)
        x[c] = x[c] xor (x[b] + x[a]).rotateLeft(9)
        x[d] = x[d] xor (x[c] + x[b]).rotateLeft(13)
        x[a] = x[a] xor (x[d] + x[c]).rotateLeft(18)
    }

    internal fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    internal fun putLe32(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v ushr 8).toByte()
        b[off + 2] = (v ushr 16).toByte()
        b[off + 3] = (v ushr 24).toByte()
    }
}
