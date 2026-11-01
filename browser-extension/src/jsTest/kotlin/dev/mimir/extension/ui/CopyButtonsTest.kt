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

class CopyButtonsTest {
    @Test
    fun copyButtonsPutMarkdownAndPlainTextOnClipboardAndTellTheUser() = runTest {
        val cipher = RcloneCipher.create("pw")
        val remote = InMemoryRemoteStorage()
        remote.write(RemotePath(CanaryVerifier.CANARY_FILENAME), cipher.encrypt(Buffer().apply { writeString("""{"magic":"mimir-vault-canary","version":1}""") }))
        remote.write(RemotePath.of(listOf("A.md").map(cipher::encryptName)), cipher.encrypt(Buffer().apply { writeString("# A **b**") }))
        val platform = FakeExtensionPlatform()
        platform.seedAuthenticatedAccount(Account("u@e.c", null))
        val controller = ExtensionController(platform, { remote }, FakeExtensionAuthHandler(platform), plainTextOf = { "PLAIN" })
        controller.initialize()
        controller.unlock("pw")
        val container = document.createElement("div") as HTMLElement
        val view = PopupDomView(container, controller, platform, isFullTabMode = false, scope = this)
        controller.state.onEach { view.render(it) }.launchIn(this)
        runCurrent()
        controller.openNote((controller.state.value as ExtensionUiState.VaultReady).flatNotes.single())
        runCurrent()

        (container.querySelector("[data-testid='copy-md-btn']") as HTMLElement).click()
        runCurrent()
        assertContains(container.querySelector("[data-testid='copy-md-btn']")!!.textContent.orEmpty(), "Скопировано")
        (container.querySelector("[data-testid='copy-text-btn']") as HTMLElement).click()
        runCurrent()

        assertEquals(listOf("# A **b**", "PLAIN"), platform.clipboard)
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
