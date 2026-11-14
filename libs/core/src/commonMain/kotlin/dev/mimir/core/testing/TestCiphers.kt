package dev.mimir.core.testing

import dev.mimir.core.RcloneCipher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Кэширующая фабрика [RcloneCipher] для тестов: избегает повторного scrypt по тем же паролю и соли. */
object TestCiphers {
    private val mutex = Mutex()
    private val cache = mutableMapOf<Pair<String, String?>, RcloneCipher>()

    suspend fun forPassword(password: String, salt: String? = null): RcloneCipher {
        val key = password to salt
        mutex.withLock { cache[key]?.let { return it } }
        val created = RcloneCipher.create(password, salt)
        return mutex.withLock { cache.getOrPut(key) { created } }
    }
}
