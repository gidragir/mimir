package dev.mimir.extension.ui

import dev.mimir.core.Account
import dev.mimir.core.CanaryVerifier
import dev.mimir.core.InMemoryRemoteStorage
import dev.mimir.core.RcloneCipher
import dev.mimir.core.RemotePath
import dev.mimir.extension.platform.ChromeExtensionPlatform
import dev.mimir.extension.platform.FakeExtensionPlatform
import kotlinx.browser.document
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.writeString
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NoteViewToggleTest {
    @Test
    fun noteViewToggleSwitchesBetweenRenderedAndRawMarkdownAndResetsOnSwitch() = runTest {
        val cipher = RcloneCipher.create("pw")
        val remote = InMemoryRemoteStorage()
        remote.write(
            RemotePath(CanaryVerifier.CANARY_FILENAME),
            cipher.encrypt(Buffer().apply { writeString("""{"magic":"mimir-vault-canary","version":1}""") }),
        )
        remote.write(
            RemotePath.of(listOf("A.md").map(cipher::encryptName)),
            cipher.encrypt(Buffer().apply { writeString("# A **b**") }),
        )
        remote.write(
            RemotePath.of(listOf("B.md").map(cipher::encryptName)),
            cipher.encrypt(Buffer().apply { writeString("# Note B") }),
        )

        val platform = FakeExtensionPlatform()
        platform.seedAuthenticatedAccount(Account("u@e.c", null))
        val controller = ExtensionController(platform, { remote }, FakeExtensionAuthHandler(platform), plainTextOf = { "PLAIN" })
        controller.initialize()
        controller.unlock("pw")

        val container = document.createElement("div") as HTMLElement
        val view = PopupDomView(container, controller, platform, isFullTabMode = false, scope = this)
        controller.state.onEach { view.render(it) }.launchIn(this)
        runCurrent()

        val ready = controller.state.value as ExtensionUiState.VaultReady
        val noteA = ready.flatNotes.first { it.name == "A.md" }
        val noteB = ready.flatNotes.first { it.name == "B.md" }

        // Открываем заметку A
        controller.openNote(noteA)
        runCurrent()

        // Старые кнопки копирования удалены
        assertNull(container.querySelector("[data-testid='copy-md-btn']"))
        assertNull(container.querySelector("[data-testid='copy-text-btn']"))

        // Кнопка переключения режимов отображения доступна
        val toggleBtn = container.querySelector("[data-testid='note-view-toggle-btn']") as? HTMLElement
        assertNotNull(toggleBtn)
        assertEquals("📖", toggleBtn.textContent)
        assertContains(toggleBtn.title, "Режим чтения")

        // По умолчанию активен режим RENDERED (отрендеренный Markdown)
        assertNotNull(container.querySelector(".markdown-body"))
        assertNull(container.querySelector("[data-testid='raw-markdown-view']"))

        // Переключаем в режим SOURCE (сырой Markdown)
        toggleBtn.click()
        runCurrent()

        val toggleBtnSource = container.querySelector("[data-testid='note-view-toggle-btn']") as HTMLElement
        assertEquals("📝", toggleBtnSource.textContent)
        assertContains(toggleBtnSource.title, "Режим Markdown")
        assertNull(container.querySelector(".markdown-body"))
        val rawView = container.querySelector("[data-testid='raw-markdown-view']") as? HTMLElement
        assertNotNull(rawView)
        val rawContent = container.querySelector("[data-testid='raw-markdown-content']") as? HTMLElement
        assertNotNull(rawContent)
        assertEquals("# A **b**", rawContent.textContent)

        // Переключаем обратно в RENDERED
        toggleBtnSource.click()
        runCurrent()

        assertNotNull(container.querySelector(".markdown-body"))
        assertNull(container.querySelector("[data-testid='raw-markdown-view']"))
        assertEquals("📖", (container.querySelector("[data-testid='note-view-toggle-btn']") as HTMLElement).textContent)

        // Снова переключаем заметку A в режим SOURCE
        (container.querySelector("[data-testid='note-view-toggle-btn']") as HTMLElement).click()
        runCurrent()
        assertNotNull(container.querySelector("[data-testid='raw-markdown-view']"))

        // Открываем другую заметку B -> режим обязан сброситься в RENDERED
        controller.openNote(noteB)
        runCurrent()

        val toggleBtnNoteB = container.querySelector("[data-testid='note-view-toggle-btn']") as HTMLElement
        assertEquals("📖", toggleBtnNoteB.textContent)
        assertNotNull(container.querySelector(".markdown-body"))
        assertNull(container.querySelector("[data-testid='raw-markdown-view']"))

        coroutineContext[kotlinx.coroutines.Job]!!.cancelChildren()
    }

    @Test
    fun clipboardApiDenialFallsBackToSelectionCopy() = runTest {
        js(
            """
            globalThis.__copies = [];
            Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: function () { return Promise.reject(new Error('NotAllowedError')); } } });
            document.execCommand = function (cmd) { globalThis.__copies.push(cmd); return true; };
            """,
        )

        ChromeExtensionPlatform().copyToClipboard("hello")

        assertEquals("copy", js("globalThis.__copies[0]"))
    }
}
