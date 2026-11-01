package dev.mimir.core

import kotlinx.io.readString

/**
 * Проверка мастер-пароля без записи в хранилище. Если известен [knownCanaryHash] из прошлой сессии,
 * пароль проверяется локально без обращений к облаку.
 */
object CanaryVerifier {
    const val CANARY_FILENAME = ".mimir_canary"
    const val CANARY_MAGIC = "mimir-vault-canary"
    private const val CANARY_HASH_PROBE = "mimir-canary-hash"
    private const val WRONG_PASSWORD = "Неверный мастер-пароль"

    sealed interface CanaryVerifyResult {
        data class Valid(val canaryHash: String) : CanaryVerifyResult
        data class Invalid(val reason: String) : CanaryVerifyResult
    }

    fun computeCanaryHash(cipher: Cipher): String =
        Sha256.digest(cipher.encryptName(CANARY_HASH_PROBE).encodeToByteArray()).toHex()

    suspend fun verifyReadOnly(
        remote: RemoteStorage,
        cipher: Cipher,
        knownCanaryHash: String? = null,
    ): CanaryVerifyResult {
        val candidateHash = computeCanaryHash(cipher)
        if (knownCanaryHash != null) {
            return if (knownCanaryHash == candidateHash) CanaryVerifyResult.Valid(candidateHash)
            else CanaryVerifyResult.Invalid(WRONG_PASSWORD)
        }
        val canaryPath = RemotePath(CANARY_FILENAME)
        if (remote.stat(canaryPath) != null) {
            val text = try {
                cipher.decrypt(remote.read(canaryPath)).readString()
            } catch (e: CryptException) {
                return CanaryVerifyResult.Invalid(WRONG_PASSWORD)
            }
            return if (CANARY_MAGIC in text) CanaryVerifyResult.Valid(candidateHash)
            else CanaryVerifyResult.Invalid(WRONG_PASSWORD)
        }
        val entries = remote.list(RemotePath(""))
        if (entries.isEmpty()) return CanaryVerifyResult.Valid(candidateHash)
        val decrypts = entries.any { entry ->
            val first = entry.path.segments.firstOrNull() ?: return@any false
            first != CANARY_FILENAME && try {
                cipher.decryptName(first)
                true
            } catch (e: CryptException) {
                false
            }
        }
        return if (decrypts) CanaryVerifyResult.Valid(candidateHash) else CanaryVerifyResult.Invalid(WRONG_PASSWORD)
    }
}
