package dev.mimir.runtime

import dev.mimir.client.DaemonLoginMethod
import dev.mimir.client.daemonLoginMethodOf
import dev.mimir.client.wireName
import dev.mimir.drive.auth.GoogleClientKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/** `account.json`: кто вошёл и каким способом. Токены хранит провайдер входа, здесь их нет. */
internal class AccountStore(dataDir: Path) {
    private val file = dataDir.resolve("account.json")
    private val legacyTokens = FileTokenStorage(dataDir.resolve("tokens.json"))
    private val json = Json { ignoreUnknownKeys = true }

    fun email(): String? = read()?.email

    /**
     * Способ входа. Старый формат (без `method`, или файла ещё нет при сохранённых токенах) определяется
     * по токенам демона: Desktop-клиент — [DaemonLoginMethod.Browser], иначе [DaemonLoginMethod.DeviceCode].
     */
    suspend fun method(): DaemonLoginMethod? {
        read()?.method?.let { daemonLoginMethodOf(it) }?.let { return it }
        val tokens = legacyTokens.load() ?: return null
        return if (tokens.clientKind == GoogleClientKind.Desktop) DaemonLoginMethod.Browser else DaemonLoginMethod.DeviceCode
    }

    fun save(email: String?, method: DaemonLoginMethod) {
        writeFileAtomically(file, json.encodeToString(AccountFile(email, method.wireName)))
    }

    fun delete() {
        Files.deleteIfExists(file)
    }

    private fun read(): AccountFile? {
        if (!Files.isRegularFile(file)) return null
        return runCatching { json.decodeFromString<AccountFile>(Files.readString(file)) }.getOrNull()
    }

    @Serializable
    private class AccountFile(val email: String?, val method: String? = null)
}
