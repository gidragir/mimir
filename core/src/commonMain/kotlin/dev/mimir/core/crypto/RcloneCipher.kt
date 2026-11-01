package dev.mimir.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered

/**
 * Шифр, бит-в-бит совместимый с rclone crypt (`filename_encryption = standard`,
 * `directory_name_encryption = true`, `filename_encoding = base32`).
 * [password] и [password2] — в открытом виде (не в obscured-формате rclone-конфига).
 */
class RcloneCipher internal constructor(
    private val dataKey: ByteArray,
    private val nameKey: ByteArray,
    private val nameTweak: ByteArray,
    private val nonceSource: () -> ByteArray = ::randomNonce,
) : Cipher {
    private val nameEme: Eme = Eme(nameKey)

    private constructor(keys: ByteArray, nonceSource: () -> ByteArray) : this(
        dataKey = keys.copyOfRange(0, 32),
        nameKey = keys.copyOfRange(32, 64),
        nameTweak = keys.copyOfRange(64, 80),
        nonceSource = nonceSource,
    )

    internal constructor(
        password: String,
        password2: String? = null,
        nonceSource: () -> ByteArray = ::randomNonce,
    ) : this(deriveKeys(password, password2), nonceSource)

    override fun encryptName(segment: String): String {
        if (segment.isEmpty()) return ""
        if (hasUnpairedSurrogates(segment)) throw CryptException("invalid name encoding")
        val raw = try {
            segment.encodeToByteArray(throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            throw CryptException("invalid name encoding", e)
        }
        val padLen = 16 - raw.size % 16
        val paddedLen = raw.size + padLen
        if (paddedLen > 16 * 128) throw CryptException("name segment exceeds 128 EME blocks")
        val padded = raw.copyOf(paddedLen)
        padded.fill(padLen.toByte(), raw.size, padded.size)
        return Base32Hex.encode(nameEme.encrypt(nameTweak, padded))
    }

    private fun hasUnpairedSurrogates(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val code = s[i].code
            if (code in 0xD800..0xDBFF) {
                if (i + 1 >= s.length || s[i + 1].code !in 0xDC00..0xDFFF) return true
                i += 2
            } else if (code in 0xDC00..0xDFFF) {
                return true
            } else {
                i++
            }
        }
        return false
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

    override fun encrypt(plain: Source): Source {
        val nonce = nonceSource()
        require(nonce.size == NONCE_SIZE) { "nonce must be $NONCE_SIZE bytes" }
        return EncryptingSource(plain, dataKey, nonce.copyOf()).buffered()
    }

    override fun decrypt(encrypted: Source): Source = DecryptingSource(encrypted, dataKey).buffered()

    /** 80 байт производного ключа (data 32 + name 32 + tweak 16): позволяет восстановить шифр без повторного scrypt. */
    fun exportKeyBytes(): ByteArray = dataKey + nameKey + nameTweak

    fun exportKeyHex(): String = exportKeyBytes().toHex()

    override fun toString(): String = "RcloneCipher"

    companion object {
        const val NONCE_SIZE = 24
        const val KEY_SIZE = 80
        val DEFAULT_SALT = byteArrayOf(
            0xA8.toByte(), 0x0D, 0xF4.toByte(), 0x3A, 0x8F.toByte(), 0xBD.toByte(), 0x03, 0x08,
            0xA7.toByte(), 0xCA.toByte(), 0xB8.toByte(), 0x3E, 0x58, 0x1F, 0x86.toByte(), 0xB1.toByte(),
        )

        private fun deriveKeys(password: String, password2: String?, checkCancelled: () -> Unit = {}): ByteArray {
            require(password.isNotEmpty()) { "password must not be empty" }
            val salt = if (password2.isNullOrEmpty()) DEFAULT_SALT else password2.encodeToByteArray()
            return Scrypt.derive(password.encodeToByteArray(), salt, 16384, 8, 1, 80, checkCancelled)
        }

        suspend fun create(
            password: String,
            password2: String? = null,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
            nonceSource: () -> ByteArray = ::randomNonce,
        ): RcloneCipher = withContext(dispatcher) {
            val context = coroutineContext
            val keys = deriveKeys(password, password2) { context.ensureActive() }
            RcloneCipher(
                dataKey = keys.copyOfRange(0, 32),
                nameKey = keys.copyOfRange(32, 64),
                nameTweak = keys.copyOfRange(64, 80),
                nonceSource = nonceSource,
            )
        }

        fun fromKeyBytes(keys: ByteArray, nonceSource: () -> ByteArray = ::randomNonce): RcloneCipher {
            require(keys.size == KEY_SIZE) { "key must be $KEY_SIZE bytes" }
            return RcloneCipher(keys, nonceSource)
        }

        fun fromKeyHex(hex: String, nonceSource: () -> ByteArray = ::randomNonce): RcloneCipher {
            require(hex.length == KEY_SIZE * 2) { "key hex must be ${KEY_SIZE * 2} characters" }
            require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "key hex contains non-hex characters" }
            val keys = ByteArray(KEY_SIZE) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            return fromKeyBytes(keys, nonceSource)
        }
    }
}

private const val NONCE_SIZE = 24
private const val BLOCK_SIZE = 65536
private val MAGIC = byteArrayOf('R'.code.toByte(), 'C'.code.toByte(), 'L'.code.toByte(), 'O'.code.toByte(), 'N'.code.toByte(), 'E'.code.toByte(), 0, 0)
private const val HEADER_SIZE = 8 + NONCE_SIZE

/** Nonce блока n: [base] + n как 192-битное little-endian число; переполнение отбрасывается (как у rclone). */
internal fun nextNonce(base: ByteArray, n: Long): ByteArray {
    val out = base.copyOf()
    var carry = n
    var i = 0
    while (carry != 0L && i < out.size) {
        val sum = (out[i].toInt() and 0xFF) + (carry and 0xFF)
        out[i] = sum.toByte()
        carry = (carry ushr 8) + (sum ushr 8)
        i++
    }
    return out
}

/** Читает до [size] байт, пока источник не исчерпан; возвращает меньше только в конце. */
private fun Source.readUpTo(size: Int): ByteArray {
    val out = ByteArray(size)
    var filled = 0
    while (filled < size) {
        val n = readAtMostTo(out, filled, size)
        if (n < 0) break
        filled += n
    }
    return if (filled == size) out else out.copyOf(filled)
}

private abstract class BlockSource : RawSource {
    private val pending = Buffer()
    private var finished = false

    /** Следующий готовый кусок вывода или null в конце потока. */
    protected abstract fun nextChunk(): ByteArray?

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0) { "byteCount < 0: $byteCount" }
        if (byteCount == 0L) return 0
        if (pending.exhausted() && !finished) {
            val chunk = nextChunk()
            if (chunk == null) finished = true else pending.write(chunk)
        }
        if (pending.exhausted()) return -1
        val n = minOf(byteCount, pending.size)
        sink.write(pending, n)
        return n
    }

    override fun close() {}
}

private class EncryptingSource(
    private val plain: Source,
    private val key: ByteArray,
    private val nonce: ByteArray,
) : BlockSource() {
    private var block = 0L
    private var headerSent = false

    override fun nextChunk(): ByteArray? {
        if (!headerSent) {
            headerSent = true
            return MAGIC + nonce
        }
        val data = plain.readUpTo(BLOCK_SIZE)
        if (data.isEmpty()) return null
        return SecretBox.seal(key, nextNonce(nonce, block++), data)
    }

    override fun close() = plain.close()
}

private class DecryptingSource(
    private val encrypted: Source,
    private val key: ByteArray,
) : BlockSource() {
    private var nonce: ByteArray? = null
    private var block = 0L

    override fun nextChunk(): ByteArray? {
        val base = nonce ?: readHeader().also { nonce = it }
        val data = encrypted.readUpTo(BLOCK_SIZE + SecretBox.TAG_SIZE)
        if (data.isEmpty()) return null
        return SecretBox.open(key, nextNonce(base, block++), data)
    }

    private fun readHeader(): ByteArray {
        val header = encrypted.readUpTo(HEADER_SIZE)
        if (header.size < HEADER_SIZE) throw CryptException("encrypted file header is truncated")
        if (!header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw CryptException("not an rclone crypt file")
        return header.copyOfRange(MAGIC.size, HEADER_SIZE)
    }

    override fun close() = encrypted.close()
}
