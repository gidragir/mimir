package dev.mimir.core

import kotlinx.io.Source

interface Cipher {
    fun encryptName(segment: String): String
    fun decryptName(segment: String): String
    fun encrypt(plain: Source): Source
    fun decrypt(encrypted: Source): Source
}
