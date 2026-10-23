package dev.mimir.core

/** AES-256, один блок за раз. Таблицы S-box — не константного времени: ключ имён защищает только имена. */
internal class Aes256(key: ByteArray) {
    private val roundKeys: ByteArray

    init {
        require(key.size == 32) { "AES-256 key must be 32 bytes" }
        val words = ByteArray(240)
        key.copyInto(words)
        val temp = ByteArray(4)
        var rcon = 1
        for (i in 8 until 60) {
            words.copyInto(temp, 0, (i - 1) * 4, i * 4)
            if (i % 8 == 0) {
                val t0 = temp[0]
                temp[0] = (SBOX[temp[1].toInt() and 0xFF].toInt() xor rcon).toByte()
                temp[1] = SBOX[temp[2].toInt() and 0xFF]
                temp[2] = SBOX[temp[3].toInt() and 0xFF]
                temp[3] = SBOX[t0.toInt() and 0xFF]
                rcon = xtime(rcon)
            } else if (i % 8 == 4) {
                for (j in 0 until 4) temp[j] = SBOX[temp[j].toInt() and 0xFF]
            }
            for (j in 0 until 4) words[i * 4 + j] = (words[(i - 8) * 4 + j].toInt() xor temp[j].toInt()).toByte()
        }
        roundKeys = words
    }

    fun encryptBlock(b: ByteArray): ByteArray {
        require(b.size == 16) { "AES block must be 16 bytes" }
        val s = b.copyOf()
        addRoundKey(s, 0)
        for (round in 1..13) {
            subBytes(s, SBOX)
            shiftRows(s)
            mixColumns(s)
            addRoundKey(s, round)
        }
        subBytes(s, SBOX)
        shiftRows(s)
        addRoundKey(s, 14)
        return s
    }

    fun decryptBlock(b: ByteArray): ByteArray {
        require(b.size == 16) { "AES block must be 16 bytes" }
        val s = b.copyOf()
        addRoundKey(s, 14)
        for (round in 13 downTo 1) {
            invShiftRows(s)
            subBytes(s, INV_SBOX)
            addRoundKey(s, round)
            invMixColumns(s)
        }
        invShiftRows(s)
        subBytes(s, INV_SBOX)
        addRoundKey(s, 0)
        return s
    }

    private fun addRoundKey(s: ByteArray, round: Int) {
        for (i in 0 until 16) s[i] = (s[i].toInt() xor roundKeys[round * 16 + i].toInt()).toByte()
    }

    private fun subBytes(s: ByteArray, box: ByteArray) {
        for (i in 0 until 16) s[i] = box[s[i].toInt() and 0xFF]
    }

    // состояние хранится по столбцам: s[4*col + row]
    private fun shiftRows(s: ByteArray) {
        val t = s.copyOf()
        for (col in 0 until 4) for (row in 0 until 4) s[4 * col + row] = t[4 * ((col + row) % 4) + row]
    }

    private fun invShiftRows(s: ByteArray) {
        val t = s.copyOf()
        for (col in 0 until 4) for (row in 0 until 4) s[4 * ((col + row) % 4) + row] = t[4 * col + row]
    }

    private fun mixColumns(s: ByteArray) {
        for (c in 0 until 4) {
            val a0 = s[4 * c].toInt() and 0xFF; val a1 = s[4 * c + 1].toInt() and 0xFF
            val a2 = s[4 * c + 2].toInt() and 0xFF; val a3 = s[4 * c + 3].toInt() and 0xFF
            s[4 * c] = (mul(a0, 2) xor mul(a1, 3) xor a2 xor a3).toByte()
            s[4 * c + 1] = (a0 xor mul(a1, 2) xor mul(a2, 3) xor a3).toByte()
            s[4 * c + 2] = (a0 xor a1 xor mul(a2, 2) xor mul(a3, 3)).toByte()
            s[4 * c + 3] = (mul(a0, 3) xor a1 xor a2 xor mul(a3, 2)).toByte()
        }
    }

    private fun invMixColumns(s: ByteArray) {
        for (c in 0 until 4) {
            val a0 = s[4 * c].toInt() and 0xFF; val a1 = s[4 * c + 1].toInt() and 0xFF
            val a2 = s[4 * c + 2].toInt() and 0xFF; val a3 = s[4 * c + 3].toInt() and 0xFF
            s[4 * c] = (mul(a0, 14) xor mul(a1, 11) xor mul(a2, 13) xor mul(a3, 9)).toByte()
            s[4 * c + 1] = (mul(a0, 9) xor mul(a1, 14) xor mul(a2, 11) xor mul(a3, 13)).toByte()
            s[4 * c + 2] = (mul(a0, 13) xor mul(a1, 9) xor mul(a2, 14) xor mul(a3, 11)).toByte()
            s[4 * c + 3] = (mul(a0, 11) xor mul(a1, 13) xor mul(a2, 9) xor mul(a3, 14)).toByte()
        }
    }

    private companion object {
        fun xtime(a: Int): Int = ((a shl 1) xor (if (a and 0x80 != 0) 0x11B else 0)) and 0xFF

        fun mul(a: Int, b: Int): Int {
            var result = 0
            var x = a
            var y = b
            while (y != 0) {
                if (y and 1 != 0) result = result xor x
                x = xtime(x)
                y = y shr 1
            }
            return result
        }

        val SBOX = ByteArray(256)
        val INV_SBOX = ByteArray(256)

        init {
            var p = 1
            var q = 1
            do {
                p = p xor (p shl 1) xor (if (p and 0x80 != 0) 0x11B else 0)
                p = p and 0xFF
                q = q xor (q shl 1)
                q = q xor (q shl 2)
                q = q xor (q shl 4)
                q = q and 0xFF
                if (q and 0x80 != 0) q = q xor 0x09
                fun rotl(v: Int, n: Int) = ((v shl n) or (v shr (8 - n))) and 0xFF
                val x = q xor rotl(q, 1) xor rotl(q, 2) xor rotl(q, 3) xor rotl(q, 4)
                SBOX[p] = (x xor 0x63).toByte()
            } while (p != 1)
            SBOX[0] = 0x63
            for (i in 0 until 256) INV_SBOX[SBOX[i].toInt() and 0xFF] = i.toByte()
        }
    }
}
