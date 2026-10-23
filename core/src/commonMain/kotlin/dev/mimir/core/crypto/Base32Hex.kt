package dev.mimir.core

/** base32hex (RFC 4648 §7) в нижнем регистре и без дополнения — кодировка имён rclone `base32`. */
internal object Base32Hex {
    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuv"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    /** Регистр не важен (как у rclone); невалидный символ, длина или ненулевые лишние биты — [CryptException]. */
    fun decode(text: String): ByteArray {
        if (text.length % 8 !in setOf(0, 2, 4, 5, 7)) throw CryptException("invalid base32 length")
        val out = ByteArray(text.length * 5 / 8)
        var buffer = 0
        var bits = 0
        var pos = 0
        for (ch in text) {
            val v = ALPHABET.indexOf(ch.lowercaseChar())
            if (v < 0) throw CryptException("invalid base32 character")
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out[pos++] = (buffer shr (bits - 8)).toByte()
                bits -= 8
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        if (buffer != 0) throw CryptException("invalid base32 trailing bits")
        return out
    }
}
