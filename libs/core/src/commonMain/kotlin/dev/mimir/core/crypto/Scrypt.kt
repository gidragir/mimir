package dev.mimir.core

internal object Scrypt {
    private const val CHECK_EVERY = 1024

    fun derive(password: ByteArray, salt: ByteArray, n: Int, r: Int, p: Int, dkLen: Int, checkCancelled: () -> Unit = {}): ByteArray {
        require(n >= 2 && (n and (n - 1)) == 0) { "scrypt N must be a power of two" }
        require(r >= 1 && p >= 1 && dkLen >= 0) { "bad scrypt parameters" }
        val blockWords = 32 * r
        val b = Pbkdf2.hmacSha256(password, salt, 1, p * 128 * r)
        val x = IntArray(blockWords)
        val v = IntArray(n * blockWords)
        val scratch = IntArray(blockWords)
        for (i in 0 until p) {
            for (w in 0 until blockWords) x[w] = Salsa20.le32(b, i * 128 * r + w * 4)
            roMix(x, v, scratch, n, r, checkCancelled)
            for (w in 0 until blockWords) Salsa20.putLe32(b, i * 128 * r + w * 4, x[w])
        }
        return Pbkdf2.hmacSha256(password, b, 1, dkLen)
    }

    /** [checkCancelled] вызывается каждые [CHECK_EVERY] шагов: отмена прерывает многосекундную деривацию. */
    private fun roMix(x: IntArray, v: IntArray, scratch: IntArray, n: Int, r: Int, checkCancelled: () -> Unit) {
        val blockWords = 32 * r
        val t = IntArray(16)
        val work = IntArray(16)
        for (i in 0 until n) {
            if (i % CHECK_EVERY == 0) checkCancelled()
            x.copyInto(v, i * blockWords)
            blockMix(x, scratch, r, t, work)
        }
        for (i in 0 until n) {
            if (i % CHECK_EVERY == 0) checkCancelled()
            val j = (x[blockWords - 16].toLong() and 0xFFFFFFFFL).rem(n.toLong()).toInt()
            for (w in 0 until blockWords) x[w] = x[w] xor v[j * blockWords + w]
            blockMix(x, scratch, r, t, work)
        }
    }

    /** scryptBlockMix на месте; [y], [t], [work] — переиспользуемые рабочие массивы. */
    private fun blockMix(b: IntArray, y: IntArray, r: Int, t: IntArray, work: IntArray) {
        b.copyInto(t, 0, (2 * r - 1) * 16, 2 * r * 16)
        for (i in 0 until 2 * r) {
            for (w in 0 until 16) t[w] = t[w] xor b[i * 16 + w]
            Salsa20.coreInPlace(t, work, 8)
            // чётные блоки — в первую половину, нечётные — во вторую
            val dst = (if (i % 2 == 0) i / 2 else r + i / 2) * 16
            t.copyInto(y, dst)
        }
        y.copyInto(b)
    }
}
