package dev.mimir.daemon

import dev.mimir.core.CryptException
import dev.mimir.core.Cipher
import dev.mimir.core.RemotePath
import dev.mimir.core.RemoteStorage
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

const val CANARY_FILENAME = ".mimir_canary"
const val CANARY_MAGIC = "mimir-vault-canary"
private const val CANARY_HASH_FILENAME = ".canary_hash"
private const val CANARY_HASH_PROBE = "mimir-canary-hash"
private val CANARY_BODY = """{"magic":"$CANARY_MAGIC","version":1}""".encodeToByteArray()

/** Введён неверный пароль шифрования (или в облаке есть данные без канарейки). */
class InvalidPasswordException(message: String) : IllegalArgumentException(message)

/**
 * Проверяет пароль по файлу-канарейке в корне [remote] (имя открытое, содержимое шифруется [cipher]).
 * [remote] — хранилище без шифрования путей. Пустое облако инициализируется канарейкой.
 * Без сети принимается только пароль, ранее подтверждённый (`.canary_hash` в [configDir]).
 */
suspend fun verifyOrInitCanary(remote: RemoteStorage, cipher: Cipher, configDir: Path) {
    val hash = canaryHash(cipher)
    val hashFile = configDir.resolve(CANARY_HASH_FILENAME)
    try {
        val path = RemotePath(CANARY_FILENAME)
        if (remote.stat(path) != null) {
            val plain = try {
                cipher.decrypt(remote.read(path)).readByteArray()
            } catch (_: CryptException) {
                throw InvalidPasswordException("Invalid encryption password")
            }
            if (!plain.decodeToString().contains(CANARY_MAGIC)) throw InvalidPasswordException("Invalid encryption password")
        } else {
            if (remote.list(RemotePath("")).isNotEmpty()) {
                throw InvalidPasswordException("Remote vault contains existing encrypted data but canary is missing")
            }
            remote.write(path, cipher.encrypt(Buffer().also { it.write(CANARY_BODY) }))
        }
        writeFileAtomically(hashFile, hash, ownerOnly = true)
    } catch (_: IOException) {
        val known = (if (Files.isRegularFile(hashFile)) Files.readString(hashFile).trim() else null)
            ?: throw SyncNotConfiguredException("Network is required to verify the encryption password for the first time")
        if (!MessageDigest.isEqual(known.encodeToByteArray(), hash.encodeToByteArray())) {
            throw InvalidPasswordException("Invalid encryption password")
        }
    }
}

// Имена шифруются детерминированно (EME), поэтому значение однозначно определяется паролем, не раскрывая его.
private fun canaryHash(cipher: Cipher): String =
    MessageDigest.getInstance("SHA-256").digest(cipher.encryptName(CANARY_HASH_PROBE).encodeToByteArray())
        .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
