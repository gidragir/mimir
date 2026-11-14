package dev.mimir.extension.ui

import dev.mimir.core.Account
import dev.mimir.core.CanaryVerifier
import dev.mimir.core.EncryptedRemoteStorage
import dev.mimir.core.RcloneCipher
import dev.mimir.core.RemotePath
import dev.mimir.core.RemoteStorage
import dev.mimir.drive.client.AuthRevokedException
import dev.mimir.drive.client.DriveAuthException
import dev.mimir.extension.platform.ExtensionPlatform
import dev.mimir.extension.vault.ExtensionVaultCache
import dev.mimir.extension.vault.VaultTreeBuilder
import dev.mimir.extension.vault.VaultTreeNode
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.readString
import kotlin.time.Clock

/**
 * Однонаправленный автомат состояний расширения. Ключ шифрования живёт только в `chrome.storage.session`,
 * в `chrome.storage.local` попадают лишь токен обновления, зашифрованный список файлов и хеш канарейки.
 */
class ExtensionController(
    private val platform: ExtensionPlatform,
    private val remoteStorageFactory: suspend () -> RemoteStorage,
    private val authHandler: ExtensionAuthHandler,
    private val cipherFactory: suspend (String) -> RcloneCipher = { RcloneCipher.create(it) },
    private val clock: Clock = Clock.System,
) {
    private val _state = MutableStateFlow<ExtensionUiState>(ExtensionUiState.Initializing)
    val state: StateFlow<ExtensionUiState> = _state.asStateFlow()

    private val cache = ExtensionVaultCache(platform)
    private var account: Account? = null
    private var cipher: RcloneCipher? = null
    private var rawStorage: RemoteStorage? = null

    init {
        // Блокировка в другой панели/вкладке расширения стирает ключ из session: гасим и этот контекст
        platform.observeSessionKey(SESSION_KEY) { value -> if (value == null) lockLocally() }
    }

    suspend fun initialize(initialPath: String? = null) {
        val acc = authHandler.currentAccount() ?: run {
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
        } catch (e: Throwable) {
            _state.value = ExtensionUiState.AuthRequired(error = e.message ?: "Не удалось войти")
            return
        }
        initialize()
    }

    suspend fun signOut() = resetAuthState(clearLocal = true)

    private suspend fun resetAuthState(clearLocal: Boolean, error: String? = null) {
        runCatching { authHandler.signOut() }
        platform.clearSession()
        if (clearLocal) platform.clearLocal()
        account = null
        cipher = null
        rawStorage = null
        _state.value = ExtensionUiState.AuthRequired(error = error)
    }

    suspend fun unlock(password: String) {
        val acc = account ?: authHandler.currentAccount() ?: run {
            _state.value = ExtensionUiState.AuthRequired()
            return
        }
        account = acc
        if (password.isEmpty()) {
            _state.value = ExtensionUiState.UnlockRequired(acc, error = "Введите мастер-пароль")
            return
        }
        _state.value = ExtensionUiState.UnlockRequired(acc, isVerifying = true)
        val candidate = cipherFactory(password)
        val knownHash = platform.getLocalString(LOCAL_CANARY_HASH)
        val result = try {
            CanaryVerifier.verifyReadOnly(raw(), candidate, knownHash)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
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
    private suspend fun handleAuthFailure(e: Throwable): Boolean {
        val revoked = e is AuthRevokedException || (e is DriveAuthException && e.statusCode == HttpStatusCode.Unauthorized)
        if (!revoked) return false
        resetAuthState(clearLocal = false, error = "Доступ к Google отозван или истёк. Войдите снова")
        return true
    }

    suspend fun refreshVault() {
        val acc = account ?: return
        val c = cipher ?: return
        val previous = _state.value as? ExtensionUiState.VaultReady
        _state.value = previous?.copy(isRefreshing = true, statusError = null) ?: ExtensionUiState.VaultLoading(acc)
        val cachedBefore = cache.load()
        try {
            val fetched = cache.fetch(raw(), cachedBefore) { folders, files ->
                if (_state.value is ExtensionUiState.VaultLoading) _state.value = ExtensionUiState.VaultLoading(acc, folders, files)
            }
            if (cipher !== c) return
            val now = clock.now().epochSeconds
            cache.save(fetched.entries, now, fetched.changeToken)
            _state.value = if (fetched.fromCache && previous != null) {
                previous.copy(isRefreshing = false, isOffline = false, statusError = null, cachedAtEpochSec = now)
            } else {
                buildVaultReady(acc, c, fetched.entries, now, if (fetched.fromCache) null else previous)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (cipher === c && !handleAuthFailure(e)) {
                _state.value = buildRefreshFailureState(acc, c, previous, cache.load(), e.message)
            }
        }
    }

    suspend fun openNote(file: VaultTreeNode.File) {
        val current = _state.value as? ExtensionUiState.VaultReady ?: return
        val c = cipher ?: return
        val segments = file.relativePath.split("/")
        val parentFolders = (1 until segments.size).map { segments.subList(0, it).joinToString("/") }.toSet()
        _state.value = current.copy(activeNote = ActiveNoteState.Loading(file), expandedFolders = current.expandedFolders + parentFolders)
        platform.setSessionString(SESSION_SELECTED_PATH, file.relativePath)
        val note = try {
            EncryptedRemoteStorage(raw(), c).read(RemotePath.of(segments)).use { ActiveNoteState.Loaded(file, it.readString()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (cipher === c && handleAuthFailure(e)) return
            ActiveNoteState.Error(file, e.message ?: "Не удалось прочитать заметку")
        }
        if (cipher !== c) return
        // «Назад» или другая заметка, нажатые пока эта грузилась, не должны отменяться её поздним ответом
        val latest = _state.value as? ExtensionUiState.VaultReady ?: return
        if ((latest.activeNote as? ActiveNoteState.Loading)?.file?.relativePath == file.relativePath) {
            _state.value = latest.copy(activeNote = note)
        }
    }

    suspend fun openWikilink(target: String) {
        val current = _state.value as? ExtensionUiState.VaultReady ?: return
        val file = VaultTreeBuilder.resolveWikilink(current.flatNotes, target) ?: run {
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
        _state.value = current.copy(searchQuery = query, filteredNotes = VaultTreeBuilder.filterNotes(current.flatNotes, query))
    }

    fun toggleFolder(folderPath: String) {
        val current = _state.value as? ExtensionUiState.VaultReady ?: return
        val expanded = if (folderPath in current.expandedFolders) current.expandedFolders - folderPath
        else current.expandedFolders + folderPath
        _state.value = current.copy(expandedFolders = expanded)
    }

    fun toggleNoteViewMode() {
        val current = _state.value as? ExtensionUiState.VaultReady ?: return
        val loaded = current.activeNote as? ActiveNoteState.Loaded ?: return
        val newMode = if (loaded.viewMode == NoteViewMode.RENDERED) NoteViewMode.SOURCE else NoteViewMode.RENDERED
        _state.value = current.copy(activeNote = loaded.copy(viewMode = newMode))
    }

    private suspend fun raw(): RemoteStorage = rawStorage ?: remoteStorageFactory().also { rawStorage = it }

    /** Дерево из кэша без сети; без кэша — первичная загрузка. */
    private suspend fun enterVault(initialPath: String?) {
        val acc = account ?: return
        val c = cipher ?: return
        val cached = cache.load()
        if (cached != null) _state.value = buildVaultReady(acc, c, cached.entries, cached.cachedAtEpochSec, previous = null)
        else refreshVault()
        val ready = _state.value as? ExtensionUiState.VaultReady ?: return
        val target = initialPath ?: return
        ready.flatNotes.firstOrNull { it.relativePath == target }?.let { openNote(it) }
    }

    private companion object {
        const val SESSION_KEY = "session.cipherKeyHex"
        const val SESSION_SELECTED_PATH = "session.selectedPath"
        const val LOCAL_CANARY_HASH = "vault.canaryHash"
    }
}
