package dev.mimir.extension.ui

import dev.mimir.core.Account
import dev.mimir.core.RcloneCipher
import dev.mimir.core.RemoteEntry
import dev.mimir.extension.vault.CachedVaultSnapshot
import dev.mimir.extension.vault.VaultTreeBuilder
import dev.mimir.extension.vault.VaultTreeNode

data class DeviceCodeUiPrompt(val userCode: String, val verificationUrl: String)

sealed interface ExtensionUiState {
    /** Чтение chrome.storage.session и chrome.storage.local */
    data object Initializing : ExtensionUiState

    /** Вход в Google Drive */
    data class AuthRequired(
        val error: String? = null,
        val isAuthorizing: Boolean = false,
        val deviceCodePrompt: DeviceCodeUiPrompt? = null,
    ) : ExtensionUiState

    /** Ввод мастер-пароля хранилища */
    data class UnlockRequired(
        val account: Account,
        val isVerifying: Boolean = false,
        val error: String? = null,
    ) : ExtensionUiState

    /** Первичная загрузка списка файлов из Google Drive */
    data class VaultLoading(
        val account: Account,
        val foldersSeen: Int = 0,
        val filesSeen: Int = 0,
    ) : ExtensionUiState

    /** Разблокированное хранилище: список и чтение заметки */
    data class VaultReady(
        val account: Account,
        val tree: List<VaultTreeNode>,
        val flatNotes: List<VaultTreeNode.File>,
        val searchQuery: String = "",
        val filteredNotes: List<VaultTreeNode.File> = flatNotes,
        val expandedFolders: Set<String> = emptySet(),
        val activeNote: ActiveNoteState? = null,
        val isRefreshing: Boolean = false,
        val isOffline: Boolean = false,
        val statusError: String? = null,
        val cachedAtEpochSec: Long? = null,
    ) : ExtensionUiState
}

enum class NoteViewMode {
    RENDERED,
    SOURCE,
}

sealed interface ActiveNoteState {
    val file: VaultTreeNode.File

    data class Loading(override val file: VaultTreeNode.File) : ActiveNoteState
    data class Loaded(
        override val file: VaultTreeNode.File,
        val rawMarkdown: String,
        val viewMode: NoteViewMode = NoteViewMode.RENDERED,
    ) : ActiveNoteState
    data class Error(override val file: VaultTreeNode.File, val message: String) : ActiveNoteState
}

internal fun buildVaultReady(
    account: Account,
    cipher: RcloneCipher,
    entries: List<RemoteEntry>,
    cachedAtEpochSec: Long,
    previous: ExtensionUiState.VaultReady?,
): ExtensionUiState.VaultReady {
    val snapshot = VaultTreeBuilder.build(entries, cipher)
    val query = previous?.searchQuery.orEmpty()
    return ExtensionUiState.VaultReady(
        account = account,
        tree = snapshot.tree,
        flatNotes = snapshot.flatNotes,
        searchQuery = query,
        filteredNotes = VaultTreeBuilder.filterNotes(snapshot.flatNotes, query),
        expandedFolders = previous?.expandedFolders.orEmpty(),
        activeNote = previous?.activeNote,
        cachedAtEpochSec = cachedAtEpochSec,
    )
}

internal fun buildRefreshFailureState(
    account: Account,
    cipher: RcloneCipher,
    previous: ExtensionUiState.VaultReady?,
    cached: CachedVaultSnapshot?,
    errorMessage: String?,
): ExtensionUiState {
    val message = errorMessage ?: "Нет связи с Google Drive"
    return when {
        previous != null -> previous.copy(isRefreshing = false, isOffline = true, statusError = message)
        cached != null -> buildVaultReady(account, cipher, cached.entries, cached.cachedAtEpochSec, previous = null)
            .copy(isOffline = true, statusError = message)
        else -> ExtensionUiState.UnlockRequired(account, error = "Не удалось загрузить хранилище: $message")
    }
}
