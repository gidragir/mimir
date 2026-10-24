package dev.mimir.daemon

import dev.mimir.drive.auth.OAuthTokens
import dev.mimir.drive.auth.TokenStorage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

class FileTokenStorage(val tokensFile: Path) : TokenStorage {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(): OAuthTokens? = mutex.withLock {
        if (!Files.isRegularFile(tokensFile)) return null
        try {
            json.decodeFromString<OAuthTokens>(Files.readString(tokensFile))
        } catch (_: SerializationException) {
            null // повреждённый файл = не авторизован; пользователь войдёт заново
        }
    }

    override suspend fun save(tokens: OAuthTokens) {
        mutex.withLock { writeFileAtomically(tokensFile, json.encodeToString(tokens), ownerOnly = true) }
    }

    override suspend fun clear() {
        mutex.withLock { Files.deleteIfExists(tokensFile) }
    }
}
