package dev.mimir.core

import kotlinx.io.Source

object NotImplementedCipher : Cipher {
    private const val MESSAGE = "rclone crypt is not implemented yet"

    override fun encryptName(segment: String): String = throw NotImplementedError(MESSAGE)
    override fun decryptName(segment: String): String = throw NotImplementedError(MESSAGE)
    override fun encrypt(plain: Source): Source = throw NotImplementedError(MESSAGE)
    override fun decrypt(encrypted: Source): Source = throw NotImplementedError(MESSAGE)
}
