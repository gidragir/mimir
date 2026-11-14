package dev.mimir.extension.ui

import dev.mimir.extension.markdown.SafeMarkdownRenderer
import dev.mimir.extension.platform.ExtensionPlatform
import dev.mimir.extension.vault.VaultTreeNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Date

/**
 * Чистая функция состояния: [render] перестраивает DOM по [ExtensionUiState], события уходят в [controller].
 * Узкий режим (Side Panel / Popup) показывает либо список, либо заметку; режим вкладки — обе колонки.
 */
class PopupDomView(
    private val root: HTMLElement,
    private val controller: ExtensionController,
    private val platform: ExtensionPlatform,
    private val isFullTabMode: Boolean,
    private val scope: CoroutineScope = MainScope(),
) {
    private val dom = DomKit(root.ownerDocument!!)
    private val auth = AuthScreens(dom, controller, platform, scope)

    private var lastVaultState: ExtensionUiState.VaultReady? = null
    private var workspaceEl: HTMLElement? = null
    private var treePaneEl: HTMLElement? = null
    private var readerPaneEl: HTMLElement? = null

    fun render(state: ExtensionUiState) {
        val search = root.querySelector("[data-testid='search-input']") as? HTMLInputElement
        val keepFocus = search != null && root.ownerDocument?.activeElement === search
        val caret = search?.selectionStart

        root.className = if (isFullTabMode) "app tab-mode" else "app panel-mode"
        val prev = lastVaultState
        val ws = workspaceEl
        if (state is ExtensionUiState.VaultReady && prev != null && ws != null && ws.parentNode === root) {
            patchVault(ws, prev, state)
            lastVaultState = state
        } else {
            root.textContent = ""
            workspaceEl = null
            treePaneEl = null
            readerPaneEl = null
            lastVaultState = null
            root.appendChild(
                when (state) {
                    ExtensionUiState.Initializing -> auth.loading("Загрузка…")
                    is ExtensionUiState.AuthRequired -> auth.authRequired(state)
                    is ExtensionUiState.UnlockRequired -> auth.unlockRequired(state)
                    is ExtensionUiState.VaultLoading ->
                        auth.loading("Загрузка списка заметок…", "Папок: ${state.foldersSeen}, файлов: ${state.filesSeen}")
                    is ExtensionUiState.VaultReady -> {
                        lastVaultState = state
                        vault(state)
                    }
                },
            )
        }
        if (keepFocus) {
            (root.querySelector("[data-testid='search-input']") as? HTMLInputElement)?.let {
                it.focus()
                caret?.let { pos -> it.setSelectionRange(pos, pos) }
            }
        }
    }

    private fun patchVault(
        ws: HTMLElement,
        prev: ExtensionUiState.VaultReady,
        next: ExtensionUiState.VaultReady,
    ) {
        val treeChanged = hasTreePaneChanged(prev, next)
        val readerChanged = prev.activeNote != next.activeNote || prev.statusError != next.statusError
        if (isFullTabMode) {
            if (treeChanged) {
                val newTree = treePane(next)
                treePaneEl?.let { ws.replaceChild(newTree, it) } ?: ws.appendChild(newTree)
                treePaneEl = newTree
            }
            if (readerChanged) {
                val newReader = readerPane(next)
                readerPaneEl?.let { ws.replaceChild(newReader, it) } ?: ws.appendChild(newReader)
                readerPaneEl = newReader
            }
            return
        }

        val wasTree = prev.activeNote == null
        val isTree = next.activeNote == null
        if (wasTree != isTree) {
            ws.textContent = ""
            if (isTree) {
                val newTree = treePane(next)
                treePaneEl = newTree
                readerPaneEl = null
                ws.appendChild(newTree)
            } else {
                val newReader = readerPane(next)
                readerPaneEl = newReader
                treePaneEl = null
                ws.appendChild(newReader)
            }
            return
        }
        if (isTree && treeChanged) {
            val newTree = treePane(next)
            treePaneEl?.let { ws.replaceChild(newTree, it) } ?: ws.appendChild(newTree)
            treePaneEl = newTree
        } else if (!isTree && readerChanged) {
            val newReader = readerPane(next)
            readerPaneEl?.let { ws.replaceChild(newReader, it) } ?: ws.appendChild(newReader)
            readerPaneEl = newReader
        }
    }

    private fun hasTreePaneChanged(
        prev: ExtensionUiState.VaultReady,
        next: ExtensionUiState.VaultReady,
    ): Boolean =
        prev.tree != next.tree ||
            prev.flatNotes != next.flatNotes ||
            prev.searchQuery != next.searchQuery ||
            prev.filteredNotes != next.filteredNotes ||
            prev.expandedFolders != next.expandedFolders ||
            prev.isRefreshing != next.isRefreshing ||
            prev.isOffline != next.isOffline ||
            prev.statusError != next.statusError ||
            prev.cachedAtEpochSec != next.cachedAtEpochSec ||
            prev.account != next.account ||
            prev.activeNote?.file?.relativePath != next.activeNote?.file?.relativePath

    private fun vault(state: ExtensionUiState.VaultReady): HTMLElement {
        val note = state.activeNote
        val container = dom.el("div", if (isFullTabMode) "workspace-split" else "workspace-single")
        workspaceEl = container
        if (isFullTabMode) {
            val tree = treePane(state).also { treePaneEl = it }
            val reader = readerPane(state).also { readerPaneEl = it }
            container.appendChild(tree)
            container.appendChild(reader)
        } else if (note == null) {
            val tree = treePane(state).also { treePaneEl = it }
            readerPaneEl = null
            container.appendChild(tree)
        } else {
            treePaneEl = null
            val reader = readerPane(state).also { readerPaneEl = it }
            container.appendChild(reader)
        }
        return container
    }

    private fun toolbarButtons(openPath: String?): List<HTMLElement> = listOf(
        dom.button("🔄", "btn icon", "refresh-btn", "Обновить список") { scope.launch { controller.refreshVault() } },
        dom.button("↗", "btn icon", "open-tab-btn", "Открыть во вкладке") { platform.openFullTab(openPath) },
        dom.button("🔒", "btn icon", "lock-btn", "Заблокировать") { scope.launch { controller.lock() } },
    )

    private fun treePane(state: ExtensionUiState.VaultReady): HTMLElement {
        val pane = dom.el("aside", "pane tree-pane", testId = "vault-tree-pane")
        val bar = dom.el("div", "toolbar")
        val search = dom.input("search", "🔍 Поиск по имени и пути...", "search-input")
        search.value = state.searchQuery
        search.oninput = { controller.updateSearchQuery(search.value) }
        bar.appendChild(search)
        toolbarButtons(state.activeNote?.file?.relativePath).forEach { bar.appendChild(it) }
        pane.appendChild(bar)
        if (state.isOffline) pane.appendChild(dom.el("div", "banner", "Нет сети: показан сохранённый список файлов", "offline-banner"))
        state.statusError?.let { pane.appendChild(dom.el("div", "banner error", it, "status-error")) }

        val list = dom.el("div", "tree")
        when {
            state.flatNotes.isEmpty() -> list.appendChild(dom.el("p", "muted empty", "В хранилище нет заметок. Проверьте папку на Google Drive.", "empty-state"))
            state.searchQuery.isNotBlank() ->
                if (state.filteredNotes.isEmpty()) list.appendChild(dom.el("p", "muted empty", "Ничего не найдено", "empty-search"))
                else state.filteredNotes.forEach { list.appendChild(searchRow(it, state)) }
            else -> appendTree(list, state.tree, 0, state)
        }
        pane.appendChild(list)

        val status = dom.el("div", "statusbar")
        val dotClass = when {
            state.statusError != null -> "status-dot error"
            state.isOffline -> "status-dot offline"
            state.isRefreshing -> "status-dot syncing"
            else -> "status-dot success"
        }
        status.appendChild(dom.el("span", dotClass, testId = "status-dot"))
        val updated = state.cachedAtEpochSec?.takeIf { it > 0 }?.let { " · обновлено ${Date(it * 1000.0).toLocaleTimeString("ru", js("({hour:'2-digit',minute:'2-digit'})"))}" }.orEmpty()
        status.appendChild(dom.el("span", "status-text", "${state.flatNotes.size} заметок$updated${if (state.isRefreshing) " · обновление…" else ""}", "status-text"))
        status.appendChild(dom.el("span", "muted status-email", state.account.email, "status-email"))
        status.appendChild(dom.button("Выйти", "btn link", "sign-out-btn") { scope.launch { controller.signOut() } })
        pane.appendChild(status)
        return pane
    }

    private fun appendTree(parent: HTMLElement, nodes: List<VaultTreeNode>, depth: Int, state: ExtensionUiState.VaultReady) {
        for (node in nodes) {
            when (node) {
                is VaultTreeNode.Folder -> {
                    val expanded = node.relativePath in state.expandedFolders
                    val row = dom.button("", "tree-row folder", title = node.name) { controller.toggleFolder(node.relativePath) }
                    row.style.paddingLeft = "${8 + depth * 14}px"
                    row.appendChild(dom.el("span", "name", "${if (expanded) "▾" else "▸"} 📁 ${node.name}"))
                    parent.appendChild(row)
                    if (expanded) appendTree(parent, node.children, depth + 1, state)
                }
                is VaultTreeNode.File -> {
                    val active = state.activeNote?.file?.relativePath == node.relativePath
                    val row = dom.button("", if (active) "tree-row file active" else "tree-row file", title = node.name) { scope.launch { controller.openNote(node) } }
                    row.style.paddingLeft = "${8 + depth * 14}px"
                    row.appendChild(dom.el("span", "name", "📄 ${node.name}"))
                    parent.appendChild(row)
                }
            }
        }
    }

    private fun searchRow(file: VaultTreeNode.File, state: ExtensionUiState.VaultReady): HTMLElement {
        val active = state.activeNote?.file?.relativePath == file.relativePath
        val row = dom.button("", if (active) "tree-row file result active" else "tree-row file result") { scope.launch { controller.openNote(file) } }
        row.appendChild(dom.el("span", "name", "📄 ${file.name}"))
        val folder = file.relativePath.substringBeforeLast('/', "")
        val date = Date(file.modifiedEpochSec * 1000.0).toLocaleDateString("ru")
        row.appendChild(dom.el("span", "muted small", if (folder.isEmpty()) date else "$folder · $date"))
        return row
    }

    private fun readerPane(state: ExtensionUiState.VaultReady): HTMLElement {
        val pane = dom.el("main", "pane reader-pane", testId = "note-reader-pane")
        val note = state.activeNote
        val bar = dom.el("div", "toolbar")
        if (!isFullTabMode) {
            bar.appendChild(dom.button("← Назад", "btn", "back-to-list-btn") { scope.launch { controller.closeActiveNote() } })
        }
        bar.appendChild(dom.el("span", "crumbs", note?.file?.relativePath ?: "", "breadcrumbs"))
        if (note is ActiveNoteState.Loaded) {
            val (icon, title) = when (note.viewMode) {
                NoteViewMode.RENDERED -> "📖" to "Режим чтения. Нажмите для показа Markdown"
                NoteViewMode.SOURCE -> "📝" to "Режим Markdown. Нажмите для показа разметки"
            }
            bar.appendChild(
                dom.button(icon, "btn icon", "note-view-toggle-btn", title) {
                    controller.toggleNoteViewMode()
                },
            )
        }
        if (isFullTabMode) {
            bar.appendChild(dom.button("🔒", "btn icon", "lock-btn", "Заблокировать") { scope.launch { controller.lock() } })
        } else {
            bar.appendChild(dom.button("↗", "btn icon", "open-tab-btn", "Открыть во вкладке") { platform.openFullTab(note?.file?.relativePath) })
        }
        pane.appendChild(bar)
        state.statusError?.let { pane.appendChild(dom.el("div", "banner error", it, "status-error")) }

        val body = dom.el("article", "note-body")
        when (note) {
            null -> body.appendChild(dom.el("p", "muted empty", "Выберите заметку в списке слева", "reader-placeholder"))
            is ActiveNoteState.Loading -> body.appendChild(auth.loading("Загрузка заметки…"))
            is ActiveNoteState.Error -> {
                body.appendChild(dom.el("p", "error", note.message, "note-error"))
                body.appendChild(dom.button("Повторить", "btn primary") { scope.launch { controller.openNote(note.file) } })
            }
            is ActiveNoteState.Loaded -> when (note.viewMode) {
                NoteViewMode.RENDERED -> body.appendChild(
                    SafeMarkdownRenderer.render(
                        root.ownerDocument!!,
                        note.rawMarkdown,
                        onWikilinkClick = { target -> scope.launch { controller.openWikilink(target) } },
                        onCopyCode = { code -> scope.launch { platform.copyToClipboard(code) } },
                    ),
                )
                NoteViewMode.SOURCE -> {
                    val pre = dom.el("pre", "raw-markdown-body", testId = "raw-markdown-view")
                    val code = dom.el("code", null, note.rawMarkdown, "raw-markdown-content")
                    pre.appendChild(code)
                    body.appendChild(pre)
                }
            }
        }
        pane.appendChild(body)
        return pane
    }
}
