package dev.mimir.core

import kotlinx.io.Source

/**
 * Шифр, бит-в-бит совместимый с rclone crypt (`filename_encryption = standard`,
 * `directory_name_encryption = true`, `filename_encoding = base32`).
 * [password] и [password2] — в открытом виде (не в obscured-формате rclone-конфига).
 */
class RcloneCipher(
    password: String,
    password2: String? = null,
    private val nonceSource: () -> ByteArray,
) : Cipher {
    private val dataKey: ByteArray
    private val nameEme: Eme
    private val nameTweak: ByteArray

    init {
        require(password.isNotEmpty()) { "password must not be empty" }
        val salt = if (password2.isNullOrEmpty()) DEFAULT_SALT else password2.encodeToByteArray()
        val keys = Scrypt.derive(password.encodeToByteArray(), salt, 16384, 8, 1, 80)
        dataKey = keys.copyOfRange(0, 32)
        nameEme = Eme(keys.copyOfRange(32, 64))
        nameTweak = keys.copyOfRange(64, 80)
    }

    override fun encryptName(segment: String): String {
        if (segment.isEmpty()) return ""
        val raw = segment.encodeToByteArray()
        val padLen = 16 - raw.size % 16
        val padded = raw.copyOf(raw.size + padLen)
        padded.fill(padLen.toByte(), raw.size, padded.size)
        return Base32Hex.encode(nameEme.encrypt(nameTweak, padded))
    }

    override fun decryptName(segment: String): String {
        if (segment.isEmpty()) return ""
        val raw = Base32Hex.decode(segment)
        if (raw.isEmpty() || raw.size % 16 != 0 || raw.size > 16 * 128) throw CryptException("invalid encrypted name length")
        val padded = nameEme.decrypt(nameTweak, raw)
        val padLen = padded.last().toInt() and 0xFF
        if (padLen < 1 || padLen > 16 || padLen > padded.size) throw CryptException("invalid name padding")
        for (i in padded.size - padLen until padded.size) {
            if ((padded[i].toInt() and 0xFF) != padLen) throw CryptException("invalid name padding")
        }
        return try {
            padded.decodeToString(0, padded.size - padLen, throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            throw CryptException("invalid encrypted name contents")
        }
    }

    override fun encrypt(plain: Source): Source = throw NotImplementedError("content crypto pending")

    override fun decrypt(encrypted: Source): Source = throw NotImplementedError("content crypto pending")

    override fun toString(): String = "RcloneCipher"

    private companion object {
        val DEFAULT_SALT = byteArrayOf(
            0xA8.toByte(), 0x0D, 0xF4.toByte(), 0x3A, 0x8F.toByte(), 0xBD.toByte(), 0x03, 0x08,
            0xA7.toByte(), 0xCA.toByte(), 0xB8.toByte(), 0x3E, 0x58, 0x1F, 0x86.toByte(), 0xB1.toByte(),
        )
    }
}
