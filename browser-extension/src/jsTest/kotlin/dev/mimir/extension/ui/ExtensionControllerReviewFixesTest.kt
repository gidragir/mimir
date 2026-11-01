package dev.mimir.extension.ui

import dev.mimir.core.Account
import dev.mimir.core.CanaryVerifier
import dev.mimir.core.InMemoryRemoteStorage
import dev.mimir.core.RcloneCipher
import dev.mimir.core.RemoteEntry
import dev.mimir.core.RemotePath
import dev.mimir.core.RemoteStorage
import dev.mimir.drive.client.AuthRevokedException
import dev.mimir.extension.platform.FakeExtensionPlatform
import dev.mimir.extension.platform.fullTabPageUrl
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Регрессии по итогам финального ревью ветки. */
class ExtensionControllerReviewFixesTest {
    private class ControllableStorage(private val inner: RemoteStorage) : RemoteStorage by inner {
        var gate: CompletableDeferred<Unit> = CompletableDeferred<Unit>().apply { complete(Unit) }
        var failure: Exception? = null

        override suspend fun listAll(root: RemotePath, onProgress: (folders: Int, files: Int) -> Unit): List<RemoteEntry> {
            gate.await()
            failure?.let { throw it }
            return inner.listAll(root, onProgress)
        }
    }

    private suspend fun populate(remote: InMemoryRemoteStorage, password: String) {
        val cipher = RcloneCipher.create(password)
        remote.write(
            RemotePath(CanaryVerifier.CANARY_FILENAME),
            cipher.encrypt(Buffer().apply { writeString("""{"magic":"mimir-vault-canary","version":1}""") }),
        )
        remote.write(
            RemotePath.of(listOf("Заметка.md").map(cipher::encryptName)),
            cipher.encrypt(Buffer().apply { writeString("текст") }),
        )
    }

    private fun newPlatform() = FakeExtensionPlatform().also {
        it.seedAuthenticatedAccount(Account(email = "u@example.com", displayName = null))
    }

    @Test
    fun refreshFinishingAfterLockDoesNotResurrectUnlockedVault() = runTest {
        val remote = InMemoryRemoteStorage().also { populate(it, "pw") }
        val storage = ControllableStorage(remote)
        val platform = newPlatform()
        val controller = ExtensionController(platform, { storage }, FakeExtensionAuthHandler(platform))
        controller.initialize()
        controller.unlock("pw")
        assertIs<ExtensionUiState.VaultReady>(controller.state.value)

        storage.gate = CompletableDeferred()
        val refresh = launch { controller.refreshVault() }
        runCurrent()
        controller.lock()
        storage.gate.complete(Unit)
        refresh.join()

        assertIs<ExtensionUiState.UnlockRequired>(controller.state.value)
    }

    @Test
    fun lockInOneContextLocksTheOtherOpenContext() = runTest {
        val remote = InMemoryRemoteStorage().also { populate(it, "pw") }
        val platform = newPlatform()
        val panel = ExtensionController(platform, { remote }, FakeExtensionAuthHandler(platform))
        panel.initialize()
        panel.unlock("pw")
        val tab = ExtensionController(platform, { remote }, FakeExtensionAuthHandler(platform))
        tab.initialize()
        assertIs<ExtensionUiState.VaultReady>(tab.state.value)

        panel.lock()

        assertIs<ExtensionUiState.UnlockRequired>(tab.state.value)
    }

    @Test
    fun emptyVaultDoesNotPinTheTypedPasswordHash() = runTest {
        val remote = InMemoryRemoteStorage()
        val platform = newPlatform()
        val controller = ExtensionController(platform, { remote }, FakeExtensionAuthHandler(platform))
        controller.initialize()
        controller.unlock("typo-pass")
        assertEquals(0, assertIs<ExtensionUiState.VaultReady>(controller.state.value).flatNotes.size)
        assertNull(platform.getLocalString("vault.canaryHash"))

        populate(remote, "right-pass")
        controller.lock()
        controller.unlock("typo-pass")
        assertNotNull(assertIs<ExtensionUiState.UnlockRequired>(controller.state.value).error)
        controller.unlock("right-pass")
        assertEquals(1, assertIs<ExtensionUiState.VaultReady>(controller.state.value).flatNotes.size)
        assertNotNull(platform.getLocalString("vault.canaryHash"))
    }

    @Test
    fun revokedRefreshTokenSendsUserBackToSignIn() = runTest {
        val remote = InMemoryRemoteStorage().also { populate(it, "pw") }
        val storage = ControllableStorage(remote)
        val platform = newPlatform()
        val controller = ExtensionController(platform, { storage }, FakeExtensionAuthHandler(platform))
        controller.initialize()
        controller.unlock("pw")

        storage.failure = AuthRevokedException(HttpStatusCode.BadRequest, "invalid_grant")
        controller.refreshVault()

        val auth = assertIs<ExtensionUiState.AuthRequired>(controller.state.value)
        assertNotNull(auth.error)
        assertFalse(auth.isAuthorizing)
    }

    @Test
    fun fullTabUrlNeverCarriesDecryptedNotePath() {
        val url = fullTabPageUrl("Секрет/План.md")
        assertEquals("popup.html?mode=tab", url)
    }
}
