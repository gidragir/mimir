package dev.mimir.core

private fun randomInt(): Int = js("crypto.getRandomValues(new Uint32Array(1))[0] | 0").unsafeCast<Int>()

internal actual fun randomNonce(): ByteArray {
    val out = ByteArray(24)
    for (word in 0 until 6) {
        val v = randomInt()
        for (b in 0 until 4) out[word * 4 + b] = (v ushr (8 * b)).toByte()
    }
    return out
}
