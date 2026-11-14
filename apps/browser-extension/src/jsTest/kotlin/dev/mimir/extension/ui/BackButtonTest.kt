package dev.mimir.extension.ui

import dev.mimir.core.Account
import dev.mimir.core.CanaryVerifier
import dev.mimir.core.InMemoryRemoteStorage
import dev.mimir.core.RcloneCipher
import dev.mimir.core.RemotePath
import dev.mimir.core.testing.TestCiphers
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
import dev.mimir.core.RemoteStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.io.Source
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BackButtonTest {
    @Test
    fun clickingBackReturnsFromNoteToList() = runTest {
        val cipher = TestCiphers.forPassword("pw")
        val remote = InMemoryRemoteStorage()
        remote.write(RemotePath(CanaryVerifier.CANARY_FILENAME), cipher.encrypt(Buffer().apply { writeString("""{"magic":"mimir-vault-canary","version":1}""") }))
        remote.write(RemotePath.of(listOf("A.md").map(cipher::encryptName)), cipher.encrypt(Buffer().apply { writeString("# A") }))
        val platform = FakeExtensionPlatform()
        platform.seedAuthenticatedAccount(Account("u@e.c", null))
        val controller = ExtensionController(platform, { remote }, FakeExtensionAuthHandler(platform), cipherFactory = TestCiphers::forPassword)
        controller.initialize()
        controller.unlock("pw")

        val container = document.createElement("div") as HTMLElement
        val view = PopupDomView(container, controller, platform, isFullTabMode = false, scope = this)
        controller.state.onEach { view.render(it) }.launchIn(this)
        runCurrent()
        controller.openNote((controller.state.value as ExtensionUiState.VaultReady).flatNotes.single())
        runCurrent()
        assertNotNull(container.querySelector("[data-testid='back-to-list-btn']"))

        (container.querySelector("[data-testid='back-to-list-btn']") as HTMLElement).click()
        runCurrent()

        assertNull(assertIs<ExtensionUiState.VaultReady>(controller.state.value).activeNote)
        assertNotNull(container.querySelector("[data-testid='vault-tree-pane']"))
        assertNull(container.querySelector("[data-testid='note-reader-pane']"))
        coroutineContext[kotlinx.coroutines.Job]!!.cancelChildren()
    }

    private class SlowRead(private val inner: RemoteStorage) : RemoteStorage by inner {
        val gate = CompletableDeferred<Unit>()
        override suspend fun read(path: RemotePath): Source {
            gate.await()
            return inner.read(path)
        }
    }

    @Test
    fun backPressedWhileNoteIsStillLoadingIsNotUndoneWhenTheNoteArrives() = runTest {
        val cipher = TestCiphers.forPassword("pw")
        val remote = InMemoryRemoteStorage()
        remote.write(RemotePath(CanaryVerifier.CANARY_FILENAME), cipher.encrypt(Buffer().apply { writeString("""{"magic":"mimir-vault-canary","version":1}""") }))
        remote.write(RemotePath.of(listOf("A.md").map(cipher::encryptName)), cipher.encrypt(Buffer().apply { writeString("# A") }))
        val slow = SlowRead(remote)
        val platform = FakeExtensionPlatform()
        platform.seedAuthenticatedAccount(Account("u@e.c", null))
        val controller = ExtensionController(platform, { slow }, FakeExtensionAuthHandler(platform), cipherFactory = TestCiphers::forPassword)
        controller.initialize()
        slow.gate.complete(Unit) // канарейку проверяем без задержки; ниже подменим гейт чтением
        controller.unlock("pw")
        val file = (controller.state.value as ExtensionUiState.VaultReady).flatNotes.single()

        val slowNote = SlowRead(remote)
        val controller2 = ExtensionController(platform, { slowNote }, FakeExtensionAuthHandler(platform), cipherFactory = TestCiphers::forPassword)
        controller2.initialize()
        val opening = launch { controller2.openNote(file) }
        runCurrent()
        assertIs<ActiveNoteState.Loading>(assertIs<ExtensionUiState.VaultReady>(controller2.state.value).activeNote)

        controller2.closeActiveNote()
        slowNote.gate.complete(Unit)
        opening.join()

        assertNull(assertIs<ExtensionUiState.VaultReady>(controller2.state.value).activeNote)
    }
}
