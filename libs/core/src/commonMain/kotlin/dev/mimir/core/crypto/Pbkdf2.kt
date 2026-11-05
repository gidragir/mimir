package dev.mimir.core

internal object Pbkdf2 {
    fun hmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        require(iterations >= 1 && dkLen >= 0) { "bad pbkdf2 parameters" }
        val out = ByteArray(dkLen)
        val blocks = (dkLen + 31) / 32
        for (i in 1..blocks) {
            val seed = salt.copyOf(salt.size + 4)
            for (b in 0 until 4) seed[salt.size + b] = (i ushr (24 - 8 * b)).toByte()
            var u = HmacSha256.mac(password, seed)
            val t = u.copyOf()
            for (iter in 1 until iterations) {
                u = HmacSha256.mac(password, u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            t.copyInto(out, (i - 1) * 32, 0, minOf(32, dkLen - (i - 1) * 32))
        }
        return out
    }
}
