package dev.mimir.core

import kotlinx.coroutines.CancellationException
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString

/**
 * Проверка мастер-пароля без записи в хранилище. Если известен [knownCanaryHash] из прошлой сессии,
 * пароль проверяется локально без обращений к облаку.
 */
object CanaryVerifier {
    const val CANARY_FILENAME = ".mimir_canary"
    const val CANARY_MAGIC = "mimir-vault-canary"
    const val CANARY_BODY = """{"magic":"$CANARY_MAGIC","version":1}"""
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
            return verifyCanaryFile(remote, canaryPath, cipher, candidateHash)
        }
        val entries = remote.list(RemotePath(""))
        if (entries.isEmpty()) return CanaryVerifyResult.Valid(candidateHash)
        return if (hasDecryptableEntry(entries, cipher)) CanaryVerifyResult.Valid(candidateHash)
        else CanaryVerifyResult.Invalid(WRONG_PASSWORD)
    }

    suspend fun verifyOrInit(
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
            return verifyCanaryFile(remote, canaryPath, cipher, candidateHash)
        }
        val entries = remote.list(RemotePath(""))
        if (entries.isNotEmpty() && !hasDecryptableEntry(entries, cipher)) {
            return CanaryVerifyResult.Invalid(WRONG_PASSWORD)
        }
        val body = Buffer().apply { writeString(CANARY_BODY) }
        remote.write(canaryPath, cipher.encrypt(body))
        return CanaryVerifyResult.Valid(candidateHash)
    }

    private suspend fun verifyCanaryFile(
        remote: RemoteStorage,
        canaryPath: RemotePath,
        cipher: Cipher,
        candidateHash: String,
    ): CanaryVerifyResult {
        val text = try {
            cipher.decrypt(remote.read(canaryPath)).use { it.readString() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return CanaryVerifyResult.Invalid(WRONG_PASSWORD)
        }
        return if (CANARY_MAGIC in text) CanaryVerifyResult.Valid(candidateHash)
        else CanaryVerifyResult.Invalid(WRONG_PASSWORD)
    }

    private fun hasDecryptableEntry(entries: List<RemoteEntry>, cipher: Cipher): Boolean =
        entries.any { entry ->
            val first = entry.path.segments.firstOrNull() ?: return@any false
            first != CANARY_FILENAME && try {
                cipher.decryptName(first)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                false
            }
        }
}
