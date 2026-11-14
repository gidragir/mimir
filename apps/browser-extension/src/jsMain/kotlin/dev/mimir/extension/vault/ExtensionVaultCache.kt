package dev.mimir.extension.vault

import dev.mimir.core.Account
import dev.mimir.core.ChangeFeedStorage
import dev.mimir.core.RemoteEntry
import dev.mimir.core.RemotePath
import dev.mimir.core.RemoteStorage
import dev.mimir.drive.client.AuthRevokedException
import dev.mimir.drive.client.DriveAuthException
import dev.mimir.extension.platform.ExtensionPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/** Профиль аккаунта в `chrome.storage.local` (`auth.account`). */
internal object AccountCodec {
    const val KEY = "auth.account"

    @Serializable
    private class Dto(val email: String, val displayName: String? = null)

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(account: Account): String = json.encodeToString(Dto.serializer(), Dto(account.email, account.displayName))

    fun decode(text: String): Account? =
        runCatching { json.decodeFromString(Dto.serializer(), text) }.getOrNull()?.let { Account(it.email, it.displayName) }

    suspend fun load(platform: ExtensionPlatform): Account? = platform.getLocalString(KEY)?.let(::decode)

    suspend fun save(platform: ExtensionPlatform, account: Account) = platform.setLocalString(KEY, encode(account))
}

/** Зашифрованная запись списка файлов для `chrome.storage.local`: пути остаются в Base32Hex. */
@Serializable
internal data class CachedRemoteEntry(
    val path: String,
    val size: Long,
    val modifiedEpochSec: Long,
    val revision: String?,
    val isDirectory: Boolean,
)

internal data class CachedVaultSnapshot(
    val entries: List<RemoteEntry>,
    val cachedAtEpochSec: Long,
    val changeToken: String?,
)

internal sealed interface IncrementalSyncCheck {
    data class Unchanged(val nextToken: String) : IncrementalSyncCheck
    data class NeedsFullList(val preListToken: String?) : IncrementalSyncCheck
}

internal data class FetchedVaultSnapshot(
    val entries: List<RemoteEntry>,
    val changeToken: String?,
    val fromCache: Boolean,
)

private suspend inline fun <T> ignoreNonAuthErrors(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: AuthRevokedException) {
    throw e
} catch (e: DriveAuthException) {
    throw e
} catch (_: Throwable) {
    null
}

internal suspend fun checkChangeFeed(storage: RemoteStorage, cachedToken: String?): IncrementalSyncCheck {
    val feed = (storage as? ChangeFeedStorage)?.takeIf { it.isChangeFeedSupported }
        ?: return IncrementalSyncCheck.NeedsFullList(preListToken = null)
    if (cachedToken != null) {
        val result = ignoreNonAuthErrors { feed.getChanges(cachedToken) }
        if (result != null) {
            return if (result.changes.isEmpty()) {
                IncrementalSyncCheck.Unchanged(result.nextToken)
            } else {
                IncrementalSyncCheck.NeedsFullList(result.nextToken)
            }
        }
    }
    return IncrementalSyncCheck.NeedsFullList(ignoreNonAuthErrors { feed.getLatestChangeToken() })
}

/** Кэш зашифрованного списка файлов хранилища и токена ленты изменений в `chrome.storage.local`. */
internal class ExtensionVaultCache(private val platform: ExtensionPlatform) {
    suspend fun load(): CachedVaultSnapshot? {
        val text = platform.getLocalString(LOCAL_ENTRIES) ?: return null
        val cached = runCatching { json.decodeFromString(ListSerializer(CachedRemoteEntry.serializer()), text) }.getOrNull()
            ?: return null
        if (cached.isEmpty()) return null
        val entries = cached.map {
            RemoteEntry(RemotePath(it.path), it.size, Instant.fromEpochSeconds(it.modifiedEpochSec), it.revision, it.isDirectory)
        }
        val cachedAt = platform.getLocalString(LOCAL_CACHED_AT)?.toLongOrNull() ?: 0L
        val changeToken = platform.getLocalString(LOCAL_CHANGE_TOKEN)?.takeIf { it.isNotBlank() }
        return CachedVaultSnapshot(entries = entries, cachedAtEpochSec = cachedAt, changeToken = changeToken)
    }

    suspend fun save(entries: List<RemoteEntry>, cachedAtEpochSec: Long, changeToken: String? = null) {
        val cached = entries.map {
            CachedRemoteEntry(it.path.value, it.size, it.modifiedAt.epochSeconds, it.revision, it.isDirectory)
        }
        platform.setLocalString(LOCAL_ENTRIES, json.encodeToString(ListSerializer(CachedRemoteEntry.serializer()), cached))
        platform.setLocalString(LOCAL_CACHED_AT, cachedAtEpochSec.toString())
        if (changeToken != null) {
            platform.setLocalString(LOCAL_CHANGE_TOKEN, changeToken)
        } else {
            platform.removeLocalKey(LOCAL_CHANGE_TOKEN)
        }
    }

    suspend fun fetch(
        storage: RemoteStorage,
        cachedBefore: CachedVaultSnapshot?,
        onProgress: (foldersSeen: Int, filesSeen: Int) -> Unit,
    ): FetchedVaultSnapshot {
        val check = checkChangeFeed(storage, cachedBefore?.changeToken)
        if (check is IncrementalSyncCheck.Unchanged && cachedBefore != null) {
            return FetchedVaultSnapshot(cachedBefore.entries, check.nextToken, fromCache = true)
        }
        val nextToken = (check as IncrementalSyncCheck.NeedsFullList).preListToken
        val entries = storage.listAll(onProgress = onProgress)
        return FetchedVaultSnapshot(entries, nextToken, fromCache = false)
    }

    companion object {
        const val LOCAL_ENTRIES = "vault.encryptedEntries"
        const val LOCAL_CACHED_AT = "vault.cachedAtEpochSec"
        const val LOCAL_CHANGE_TOKEN = "vault.changeToken"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
