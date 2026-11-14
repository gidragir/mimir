package dev.mimir.runtime

import dev.mimir.core.CanaryVerifier
import dev.mimir.core.Cipher
import dev.mimir.core.RemoteStorage
import dev.mimir.core.RemoteUnavailableException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

const val CANARY_FILENAME = CanaryVerifier.CANARY_FILENAME
const val CANARY_MAGIC = CanaryVerifier.CANARY_MAGIC
private const val CANARY_HASH_FILENAME = ".canary_hash"

/** Введён неверный пароль шифрования (или в облаке есть данные без канарейки). */
typealias InvalidPasswordException = dev.mimir.client.InvalidPasswordException

/** Синхронизация не может стартовать: не хватает настройки или сети для первой проверки. */
typealias SyncNotConfiguredException = dev.mimir.client.SyncNotConfiguredException

/**
 * Проверяет пароль по файлу-канарейке в корне [remote] через [CanaryVerifier.verifyOrInit].
 * Пустое облако инициализируется канарейкой. Без сети принимается только пароль,
 * ранее подтверждённый (`.canary_hash` в [configDir]).
 */
suspend fun verifyOrInitCanary(remote: RemoteStorage, cipher: Cipher, configDir: Path) {
    val hashFile = configDir.resolve(CANARY_HASH_FILENAME)
    try {
        when (val result = CanaryVerifier.verifyOrInit(remote, cipher)) {
            is CanaryVerifier.CanaryVerifyResult.Valid ->
                writeFileAtomically(hashFile, result.canaryHash, ownerOnly = true)
            is CanaryVerifier.CanaryVerifyResult.Invalid ->
                throw InvalidPasswordException(result.reason)
        }
    } catch (_: IOException) {
        verifyOfflineCanary(remote, cipher, hashFile)
    } catch (_: RemoteUnavailableException) {
        verifyOfflineCanary(remote, cipher, hashFile)
    }
}

private suspend fun verifyOfflineCanary(remote: RemoteStorage, cipher: Cipher, hashFile: Path) {
    val known = (if (Files.isRegularFile(hashFile)) Files.readString(hashFile).trim() else null)
        ?: throw SyncNotConfiguredException("Network is required to verify the encryption password for the first time")
    val result = CanaryVerifier.verifyOrInit(remote, cipher, knownCanaryHash = known)
    if (result is CanaryVerifier.CanaryVerifyResult.Invalid) {
        throw InvalidPasswordException(result.reason)
    }
}
