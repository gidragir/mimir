package dev.mimir.core

/** Poly1305 на 26-битных лимбах (как poly1305-donna-32), без ветвлений по секретным данным. */
internal object Poly1305 {
    private const val MASK = 0x3ffffffL

    private fun le(b: ByteArray, off: Int): Long = Salsa20.le32(b, off).toLong() and 0xFFFFFFFFL

    fun mac(key32: ByteArray, data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ByteArray {
        require(key32.size == 32) { "poly1305 key must be 32 bytes" }
        require(offset >= 0 && length >= 0 && offset + length <= data.size) {
            "invalid poly1305 range: offset=$offset length=$length size=${data.size}"
        }
        val t0 = le(key32, 0); val t1 = le(key32, 4); val t2 = le(key32, 8); val t3 = le(key32, 12)
        val r0 = t0 and 0x3ffffffL
        val r1 = ((t0 ushr 26) or (t1 shl 6)) and 0x3ffff03L
        val r2 = ((t1 ushr 20) or (t2 shl 12)) and 0x3ffc0ffL
        val r3 = ((t2 ushr 14) or (t3 shl 18)) and 0x3f03fffL
        val r4 = (t3 ushr 8) and 0x00fffffL
        val s1 = r1 * 5; val s2 = r2 * 5; val s3 = r3 * 5; val s4 = r4 * 5

        var h0 = 0L; var h1 = 0L; var h2 = 0L; var h3 = 0L; var h4 = 0L
        var pos = offset
        val end = offset + length
        val block = ByteArray(16)
        while (pos < end) {
            val n = minOf(16, end - pos)
            val m0: Long
            val m1: Long
            val m2: Long
            val m3: Long
            val hibit: Long
            if (n == 16) {
                m0 = le(data, pos); m1 = le(data, pos + 4); m2 = le(data, pos + 8); m3 = le(data, pos + 12)
                hibit = 1L shl 24
            } else {
                block.fill(0)
                data.copyInto(block, 0, pos, pos + n)
                block[n] = 1
                m0 = le(block, 0); m1 = le(block, 4); m2 = le(block, 8); m3 = le(block, 12)
                hibit = 0
            }
            h0 += m0 and MASK
            h1 += ((m0 ushr 26) or (m1 shl 6)) and MASK
            h2 += ((m1 ushr 20) or (m2 shl 12)) and MASK
            h3 += ((m2 ushr 14) or (m3 shl 18)) and MASK
            h4 += (m3 ushr 8) or hibit

            val d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
            var d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
            var d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
            var d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
            var d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0
            var c = d0 ushr 26; h0 = d0 and MASK
            d1 += c; c = d1 ushr 26; h1 = d1 and MASK
            d2 += c; c = d2 ushr 26; h2 = d2 and MASK
            d3 += c; c = d3 ushr 26; h3 = d3 and MASK
            d4 += c; c = d4 ushr 26; h4 = d4 and MASK
            h0 += c * 5; c = h0 ushr 26; h0 = h0 and MASK
            h1 += c
            pos += n
        }

        var c = h1 ushr 26; h1 = h1 and MASK
        h2 += c; c = h2 ushr 26; h2 = h2 and MASK
        h3 += c; c = h3 ushr 26; h3 = h3 and MASK
        h4 += c; c = h4 ushr 26; h4 = h4 and MASK
        h0 += c * 5; c = h0 ushr 26; h0 = h0 and MASK
        h1 += c

        var g0 = h0 + 5; c = g0 ushr 26; g0 = g0 and MASK
        var g1 = h1 + c; c = g1 ushr 26; g1 = g1 and MASK
        var g2 = h2 + c; c = g2 ushr 26; g2 = g2 and MASK
        var g3 = h3 + c; c = g3 ushr 26; g3 = g3 and MASK
        var g4 = h4 + c - (1L shl 26)

        val mask = (g4 ushr 63) - 1 // 0, если g4 < 0 (h < p), иначе все единицы
        val nmask = mask.inv()
        g0 = g0 and mask; g1 = g1 and mask; g2 = g2 and mask; g3 = g3 and mask; g4 = g4 and mask
        h0 = (h0 and nmask) or g0
        h1 = (h1 and nmask) or g1
        h2 = (h2 and nmask) or g2
        h3 = (h3 and nmask) or g3
        h4 = (h4 and nmask) or g4

        val w0 = (h0 or (h1 shl 26)) and 0xFFFFFFFFL
        val w1 = ((h1 ushr 6) or (h2 shl 20)) and 0xFFFFFFFFL
        val w2 = ((h2 ushr 12) or (h3 shl 14)) and 0xFFFFFFFFL
        val w3 = ((h3 ushr 18) or (h4 shl 8)) and 0xFFFFFFFFL

        var f = w0 + le(key32, 16)
        val out = ByteArray(16)
        Salsa20.putLe32(out, 0, f.toInt())
        f = w1 + le(key32, 20) + (f ushr 32)
        Salsa20.putLe32(out, 4, f.toInt())
        f = w2 + le(key32, 24) + (f ushr 32)
        Salsa20.putLe32(out, 8, f.toInt())
        f = w3 + le(key32, 28) + (f ushr 32)
        Salsa20.putLe32(out, 12, f.toInt())
        return out
    }
}
