package dev.mimir.extension.ui

import dev.mimir.core.Account
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
        val collapsedFolders: Set<String> = emptySet(),
        val activeNote: ActiveNoteState? = null,
        val isRefreshing: Boolean = false,
        val isOffline: Boolean = false,
        val statusError: String? = null,
        val cachedAtEpochSec: Long? = null,
    ) : ExtensionUiState
}

sealed interface ActiveNoteState {
    val file: VaultTreeNode.File

    data class Loading(override val file: VaultTreeNode.File) : ActiveNoteState
    data class Loaded(
        override val file: VaultTreeNode.File,
        val rawMarkdown: String,
        val plainText: String,
    ) : ActiveNoteState
    data class Error(override val file: VaultTreeNode.File, val message: String) : ActiveNoteState
}
