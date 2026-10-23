package dev.mimir.core

/**
 * EME-32 (Halevi–Rogaway) на AES-256, совместимый с github.com/rfjakob/eme, который использует rclone.
 * Умножение на 2 в GF(2^128) — над байтами в порядке little-endian.
 */
internal class Eme(key: ByteArray) {
    private val aes = Aes256(key)

    fun encrypt(tweak: ByteArray, data: ByteArray): ByteArray = transform(tweak, data, encrypt = true)

    fun decrypt(tweak: ByteArray, data: ByteArray): ByteArray = transform(tweak, data, encrypt = false)

    private fun transform(tweak: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
        require(tweak.size == 16) { "EME tweak must be 16 bytes" }
        require(data.isNotEmpty() && data.size % 16 == 0 && data.size <= 16 * 128) {
            "EME data must be 1..128 blocks of 16 bytes"
        }
        val m = data.size / 16
        val ecb: (ByteArray) -> ByteArray = if (encrypt) aes::encryptBlock else aes::decryptBlock

        val lTable = ArrayList<ByteArray>(m)
        var li = aes.encryptBlock(ByteArray(16))
        repeat(m) {
            li = multByTwo(li)
            lTable.add(li)
        }

        val c = Array(m) { j -> ecb(xor(data.copyOfRange(j * 16, j * 16 + 16), lTable[j])) }

        var mp = xor(c[0], tweak)
        for (j in 1 until m) mp = xor(mp, c[j])
        val mc = ecb(mp)
        var mm = xor(mp, mc)
        for (j in 1 until m) {
            mm = multByTwo(mm)
            c[j] = xor(c[j], mm)
        }
        var first = xor(mc, tweak)
        for (j in 1 until m) first = xor(first, c[j])
        c[0] = first

        val out = ByteArray(data.size)
        for (j in 0 until m) xor(ecb(c[j]), lTable[j]).copyInto(out, j * 16)
        return out
    }

    private fun xor(a: ByteArray, b: ByteArray) = ByteArray(16) { (a[it].toInt() xor b[it].toInt()).toByte() }

    private fun multByTwo(input: ByteArray): ByteArray {
        val out = ByteArray(16)
        out[0] = (input[0].toInt() shl 1).toByte()
        if (input[15].toInt() and 0x80 != 0) out[0] = (out[0].toInt() xor 135).toByte()
        for (j in 1 until 16) {
            out[j] = (input[j].toInt() shl 1).toByte()
            if (input[j - 1].toInt() and 0x80 != 0) out[j] = (out[j] + 1).toByte()
        }
        return out
    }
}
