package dev.mimir.core

import java.security.SecureRandom

private val secureRandom = SecureRandom()

internal actual fun randomNonce(): ByteArray = ByteArray(24).also(secureRandom::nextBytes)
