package dev.mimir.extension.ui

import dev.mimir.core.Account
import dev.mimir.core.CanaryVerifier
import dev.mimir.core.EncryptedRemoteStorage
import dev.mimir.core.RcloneCipher
import dev.mimir.core.RemoteEntry
import dev.mimir.core.RemotePath
import dev.mimir.core.RemoteStorage
import dev.mimir.drive.client.AuthRevokedException
import dev.mimir.drive.client.DriveAuthException
import dev.mimir.extension.platform.ExtensionPlatform
import dev.mimir.extension.vault.VaultTreeBuilder
import dev.mimir.extension.vault.VaultTreeNode
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.readString
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant

interface ExtensionAuthHandler {
    suspend fun currentAccount(): Account?
    suspend fun signInWithWebAuthFlow(): Account
    suspend fun startDeviceCode(onPrompt: (DeviceCodeUiPrompt) -> Unit): Account
    suspend fun signOut()
}

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

/**
 * Однонаправленный автомат состояний расширения. Ключ шифрования живёт только в `chrome.storage.session`,
 * в `chrome.storage.local` попадают лишь токен обновления, зашифрованный список файлов и хеш канарейки.
 */
class ExtensionController(
    private val platform: ExtensionPlatform,
    private val remoteStorageFactory: suspend () -> RemoteStorage,
    private val authHandler: ExtensionAuthHandler,
    private val plainTextOf: (String) -> String = { it },
    private val clock: Clock = Clock.System,
) {
    private val _state = MutableStateFlow<ExtensionUiState>(ExtensionUiState.Initializing)
    val state: StateFlow<ExtensionUiState> = _state.asStateFlow()

    private var account: Account? = null
    private var cipher: RcloneCipher? = null
    private var rawStorage: RemoteStorage? = null

    init {
        // Блокировка в другой панели/вкладке расширения стирает ключ из session: гасим и этот контекст
        platform.observeSessionKey(SESSION_KEY) { value -> if (value == null) lockLocally() }
    }

    suspend fun initialize(initialPath: String? = null) {
        val acc = authHandler.currentAccount()
        if (acc == null) {
            _state.value = ExtensionUiState.AuthRequired()
            return
        }
        account = acc
        val keyHex = platform.getSessionString(SESSION_KEY)
        val restored = keyHex?.let { runCatching { RcloneCipher.fromKeyHex(it) }.getOrNull() }
        if (restored == null) {
            if (keyHex != null) platform.removeSessionKey(SESSION_KEY)
            _state.value = ExtensionUiState.UnlockRequired(acc)
            return
        }
        cipher = restored
        enterVault(initialPath ?: platform.getSessionString(SESSION_SELECTED_PATH))
    }

    suspend fun signInWithWebAuthFlow() = signIn { authHandler.signInWithWebAuthFlow() }

    suspend fun startDeviceCodeFlow() = signIn {
        authHandler.startDeviceCode { prompt ->
            _state.value = ExtensionUiState.AuthRequired(isAuthorizing = true, deviceCodePrompt = prompt)
        }
    }

    private suspend fun signIn(block: suspend () -> Account) {
        _state.value = ExtensionUiState.AuthRequired(isAuthorizing = true)
        try {
            account = block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = ExtensionUiState.AuthRequired(error = e.message ?: "Не удалось войти")
            return
        }
        initialize()
    }

    suspend fun signOut() {
        runCatching { authHandler.signOut() }
        platform.clearSession()
        platform.clearLocal()
        account = null
        cipher = null
        rawStorage = null
        _state.value = ExtensionUiState.AuthRequired()
    }

    suspend fun unlock(password: String) {
        val acc = account ?: authHandler.currentAccount()
        if (acc == null) {
            _state.value = ExtensionUiState.AuthRequired()
            return
        }
        account = acc
        if (password.isEmpty()) {
            _state.value = ExtensionUiState.UnlockRequired(acc, error = "Введите мастер-пароль")
            return
        }
        _state.value = ExtensionUiState.UnlockRequired(acc, isVerifying = true)
        val candidate = RcloneCipher.create(password)
        val knownHash = platform.getLocalString(LOCAL_CANARY_HASH)
        val result = try {
            CanaryVerifier.verifyReadOnly(raw(), candidate, knownHash)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!handleAuthFailure(e)) {
                _state.value = ExtensionUiState.UnlockRequired(acc, error = "Не удалось проверить пароль: ${e.message}")
            }
            return
        }
        when (result) {
            is CanaryVerifier.CanaryVerifyResult.Invalid ->
                _state.value = ExtensionUiState.UnlockRequired(acc, error = result.reason)
            is CanaryVerifier.CanaryVerifyResult.Valid -> {
                platform.setSessionString(SESSION_KEY, candidate.exportKeyHex())
                cipher = candidate
                enterVault(null)
                // Пустое хранилище подтверждает любой пароль: хеш закрепляем, только увидев расшифровавшиеся заметки
                val hasNotes = (_state.value as? ExtensionUiState.VaultReady)?.flatNotes?.isNotEmpty() == true
                if (knownHash != null || hasNotes) platform.setLocalString(LOCAL_CANARY_HASH, result.canaryHash)
            }
        }
    }

    /** Очищает ключ и открытую заметку; токен обновления и зашифрованный кэш дерева остаются. */
    suspend fun lock() {
        lockLocally()
        platform.clearSession()
    }

    private fun lockLocally() {
        if (cipher == null) return
        cipher = null
        _state.value = account?.let { ExtensionUiState.UnlockRequired(it) } ?: ExtensionUiState.AuthRequired()
    }

    /** Отозванный или просроченный refresh-токен: возвращаем к входу, а не показываем «нет сети». */
    private suspend fun handleAuthFailure(e: Exception): Boolean {
        val revoked = e is AuthRevokedException || (e is DriveAuthException && e.statusCode == HttpStatusCode.Unauthorized)
        if (!revoked) return false
        runCatching { authHandler.signOut() }
        platform.clearSession()
        cipher = null
        rawStorage = null
        account = null
        _state.value = ExtensionUiState.AuthRequired(error = "Доступ к Google отозван или истёк. Войдите снова")
        return true
    }

    suspend fun refreshVault() {
        val acc = account ?: return
        val c = cipher ?: return
        val previous = _state.value as? ExtensionUiState.VaultReady
        _state.value = previous?.copy(isRefreshing = true, statusError = null) ?: ExtensionUiState.VaultLoading(acc)
        val entries = try {
            raw().listAll { folders, files ->
                if (_state.value is ExtensionUiState.VaultLoading) {
                    _state.value = ExtensionUiState.VaultLoading(acc, folders, files)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cipher !== c) return
            if (handleAuthFailure(e)) return
            val message = e.message ?: "Нет связи с Google Drive"
            val cached = loadCache()
            _state.value = when {
                previous != null -> previous.copy(isRefreshing = false, isOffline = true, statusError = message)
                cached != null -> readyFrom(acc, c, cached.first, cached.second, previous = null)
                    .copy(isOffline = true, statusError = message)
                else -> ExtensionUiState.UnlockRequired(acc, error = "Не удалось загрузить хранилище: $message")
            }
            return
        }
        if (cipher !== c) return
        val now = clock.now().epochSeconds
        saveCache(entries, now)
        _state.value = readyFrom(acc, c, entries, now, previous)
    }

    suspend fun openNote(file: VaultTreeNode.File) {
        val current = _state.value as? ExtensionUiState.VaultReady ?: return
        val c = cipher ?: return
        _state.value = current.copy(activeNote = ActiveNoteState.Loading(file))
        platform.setSessionString(SESSION_SELECTED_PATH, file.relativePath)
        val note = try {
            val source = EncryptedRemoteStorage(raw(), c).read(RemotePath.of(file.relativePath.split("/")))
            val text = try {
                source.readString()
            } finally {
                source.close()
            }
            ActiveNoteState.Loaded(file, text, plainTextOf(text))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cipher === c && handleAuthFailure(e)) return
            ActiveNoteState.Error(file, e.message ?: "Не удалось прочитать заметку")
        }
        if (cipher !== c) return
        (_state.value as? ExtensionUiState.VaultReady)?.let { _state.value = it.copy(activeNote = note) }
    }

    suspend fun openWikilink(target: String) {
        val current = _state.value as? ExtensionUiState.VaultReady ?: return
        val file = VaultTreeBuilder.resolveWikilink(current.flatNotes, target)
        if (file == null) {
            _state.value = current.copy(statusError = "Заметка не найдена: $target")
            return
        }
        openNote(file)
    }

    suspend fun closeActiveNote() {
        platform.removeSessionKey(SESSION_SELECTED_PATH)
        (_state.value as? ExtensionUiState.VaultReady)?.let { _state.value = it.copy(activeNote = null) }
    }

    fun updateSearchQuery(query: String) {
        val current = _state.value as? ExtensionUiState.VaultReady ?: return
        _state.value = current.copy(
            searchQuery = query,
            filteredNotes = VaultTreeBuilder.filterNotes(current.flatNotes, query),
        )
    }

    fun toggleFolder(folderPath: String) {
        val current = _state.value as? ExtensionUiState.VaultReady ?: return
        val collapsed = if (folderPath in current.collapsedFolders) current.collapsedFolders - folderPath
        else current.collapsedFolders + folderPath
        _state.value = current.copy(collapsedFolders = collapsed)
    }

    private suspend fun raw(): RemoteStorage = rawStorage ?: remoteStorageFactory().also { rawStorage = it }

    /** Дерево из кэша без сети; без кэша — первичная загрузка. */
    private suspend fun enterVault(initialPath: String?) {
        val acc = account ?: return
        val c = cipher ?: return
        val cached = loadCache()
        if (cached != null) {
            _state.value = readyFrom(acc, c, cached.first, cached.second, previous = null)
        } else {
            refreshVault()
        }
        val ready = _state.value as? ExtensionUiState.VaultReady ?: return
        val target = initialPath ?: return
        ready.flatNotes.firstOrNull { it.relativePath == target }?.let { openNote(it) }
    }

    private fun readyFrom(
        acc: Account,
        c: RcloneCipher,
        entries: List<RemoteEntry>,
        cachedAt: Long,
        previous: ExtensionUiState.VaultReady?,
    ): ExtensionUiState.VaultReady {
        val snapshot = VaultTreeBuilder.build(entries, c)
        val query = previous?.searchQuery.orEmpty()
        return ExtensionUiState.VaultReady(
            account = acc,
            tree = snapshot.tree,
            flatNotes = snapshot.flatNotes,
            searchQuery = query,
            filteredNotes = VaultTreeBuilder.filterNotes(snapshot.flatNotes, query),
            collapsedFolders = previous?.collapsedFolders.orEmpty(),
            activeNote = previous?.activeNote,
            cachedAtEpochSec = cachedAt,
        )
    }

    private suspend fun loadCache(): Pair<List<RemoteEntry>, Long>? {
        val text = platform.getLocalString(LOCAL_ENTRIES) ?: return null
        val cached = runCatching { json.decodeFromString(ListSerializer(CachedRemoteEntry.serializer()), text) }.getOrNull()
            ?: return null
        if (cached.isEmpty()) return null
        val entries = cached.map {
            RemoteEntry(RemotePath(it.path), it.size, Instant.fromEpochSeconds(it.modifiedEpochSec), it.revision, it.isDirectory)
        }
        return entries to (platform.getLocalString(LOCAL_CACHED_AT)?.toLongOrNull() ?: 0L)
    }

    private suspend fun saveCache(entries: List<RemoteEntry>, cachedAt: Long) {
        val cached = entries.map {
            CachedRemoteEntry(it.path.value, it.size, it.modifiedAt.epochSeconds, it.revision, it.isDirectory)
        }
        platform.setLocalString(LOCAL_ENTRIES, json.encodeToString(ListSerializer(CachedRemoteEntry.serializer()), cached))
        platform.setLocalString(LOCAL_CACHED_AT, cachedAt.toString())
    }

    private companion object {
        const val SESSION_KEY = "session.cipherKeyHex"
        const val SESSION_SELECTED_PATH = "session.selectedPath"
        const val LOCAL_ENTRIES = "vault.encryptedEntries"
        const val LOCAL_CACHED_AT = "vault.cachedAtEpochSec"
        const val LOCAL_CANARY_HASH = "vault.canaryHash"
        val json = Json { ignoreUnknownKeys = true }
    }
}
