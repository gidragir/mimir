package dev.mimir.extension.ui

import dev.mimir.core.Account
import dev.mimir.core.CanaryVerifier
import dev.mimir.core.ChangeFeedStorage
import dev.mimir.core.CountingRemoteStorage
import dev.mimir.core.InMemoryRemoteStorage
import dev.mimir.core.RcloneCipher
import dev.mimir.core.RemoteChange
import dev.mimir.core.RemoteChangesResult
import dev.mimir.core.RemoteEntry
import dev.mimir.core.RemotePath
import dev.mimir.core.RemoteStorage
import dev.mimir.core.testing.TestCiphers
import dev.mimir.extension.platform.FakeExtensionPlatform
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtensionControllerTest {
    @Test
    fun fullLifecycleUnlockBrowseReadBackAndLockSharesSessionKey() = runTest {
        val cipher = TestCiphers.forPassword("master-pass")
        val rawRemote = InMemoryRemoteStorage()
        rawRemote.write(
            RemotePath(CanaryVerifier.CANARY_FILENAME),
            cipher.encrypt(Buffer().apply { writeString("""{"magic":"mimir-vault-canary","version":1}""") }),
        )
        val noteEncPath = RemotePath.of(listOf("Проекты", "План.md").map(cipher::encryptName))
        rawRemote.write(
            noteEncPath,
            cipher.encrypt(Buffer().apply { writeString("# План\nСм. [[Вторая]]") }),
        )
        val secondEncPath = RemotePath.of(listOf("Вторая.md").map(cipher::encryptName))
        rawRemote.write(
            secondEncPath,
            cipher.encrypt(Buffer().apply { writeString("Текст второй заметки") }),
        )

        val countingRemote = CountingRemoteStorage(rawRemote)
        val platform = FakeExtensionPlatform()
        platform.seedAuthenticatedAccount(Account(email = "user@example.com", displayName = "User"))

        val controller = ExtensionController(
            platform = platform,
            remoteStorageFactory = { countingRemote },
            authHandler = FakeExtensionAuthHandler(platform),
            cipherFactory = TestCiphers::forPassword,
        )

        controller.initialize()
        val unlockState = assertIs<ExtensionUiState.UnlockRequired>(controller.state.value)
        assertEquals("user@example.com", unlockState.account.email)

        // Неверный пароль отклоняется
        controller.unlock("wrong-pass")
        val failedUnlock = assertIs<ExtensionUiState.UnlockRequired>(controller.state.value)
        assertNotNull(failedUnlock.error)

        // Верный пароль загружает дерево заметок
        controller.unlock("master-pass")
        val ready = assertIs<ExtensionUiState.VaultReady>(controller.state.value)
        assertEquals(2, ready.flatNotes.size)
        assertNull(ready.activeNote)

        // Открытие заметки (Вариант А: Список -> Заметка)
        val planFile = ready.flatNotes.first { it.name == "План.md" }
        controller.openNote(planFile)
        val withNote = assertIs<ExtensionUiState.VaultReady>(controller.state.value)
        val loaded = assertIs<ActiveNoteState.Loaded>(withNote.activeNote)
        assertEquals("# План\nСм. [[Вторая]]", loaded.rawMarkdown)

        // Переход по [[Вторая]] wikilink
        controller.openWikilink("Вторая")
        val afterWiki = assertIs<ExtensionUiState.VaultReady>(controller.state.value)
        val loadedSecond = assertIs<ActiveNoteState.Loaded>(afterWiki.activeNote)
        assertEquals("Текст второй заметки", loadedSecond.rawMarkdown)

        // Возврат к списку кнопкой «← Назад»
        controller.closeActiveNote()
        val backToList = assertIs<ExtensionUiState.VaultReady>(controller.state.value)
        assertNull(backToList.activeNote)

        // Открытие второй вкладки (новый инстанс контроллера поверх того же platform):
        // 0 сетевых вызовов listAll и без повторного ввода пароля!
        val listCallsBeforeSecondTab = countingRemote.listCalls
        val tabController = ExtensionController(
            platform = platform,
            remoteStorageFactory = { countingRemote },
            authHandler = FakeExtensionAuthHandler(platform),
            cipherFactory = TestCiphers::forPassword,
        )
        tabController.initialize(initialPath = "Проекты/План.md")
        val tabReady = assertIs<ExtensionUiState.VaultReady>(tabController.state.value)
        assertEquals(listCallsBeforeSecondTab, countingRemote.listCalls)
        val tabNote = assertIs<ActiveNoteState.Loaded>(tabReady.activeNote)
        assertEquals("# План\nСм. [[Вторая]]", tabNote.rawMarkdown)

        // Блокировка очищает сессию, но оставляет аккаунт и зашифрованный кэш
        controller.lock()
        assertIs<ExtensionUiState.UnlockRequired>(controller.state.value)
        assertNull(platform.getSessionString("session.cipherKeyHex"))
        assertNotNull(platform.getLocalString("vault.encryptedEntries"))
    }

    private suspend fun vaultFixture(password: String): InMemoryRemoteStorage {
        val cipher = TestCiphers.forPassword(password)
        val remote = InMemoryRemoteStorage()
        remote.write(
            RemotePath(CanaryVerifier.CANARY_FILENAME),
            cipher.encrypt(Buffer().apply { writeString("""{"magic":"mimir-vault-canary","version":1}""") }),
        )
        remote.write(
            RemotePath.of(listOf("Заметка.md").map(cipher::encryptName)),
            cipher.encrypt(Buffer().apply { writeString("текст") }),
        )
        return remote
    }

    @Test
    fun wrongPasswordWithSavedCanaryHashIsRejectedWithoutAnyNetworkCall() = runTest {
        val counting = CountingRemoteStorage(vaultFixture("right-pass"))
        val platform = FakeExtensionPlatform()
        platform.seedAuthenticatedAccount(Account(email = "u@example.com", displayName = null))
        val controller = ExtensionController(platform, { counting }, FakeExtensionAuthHandler(platform), cipherFactory = TestCiphers::forPassword)
        controller.initialize()
        controller.unlock("right-pass")
        assertIs<ExtensionUiState.VaultReady>(controller.state.value)
        controller.lock()

        counting.reset()
        controller.unlock("wrong-pass")
        assertNotNull(assertIs<ExtensionUiState.UnlockRequired>(controller.state.value).error)
        assertEquals(0, counting.statCalls + counting.readCalls + counting.listCalls + counting.listAllCalls)
    }

    @Test
    fun lockWhileNoteOpenKeepsRefreshTokenAndCacheAndClearsSelectedNote() = runTest {
        val platform = FakeExtensionPlatform()
        platform.seedAuthenticatedAccount(Account(email = "u@example.com", displayName = null))
        platform.setLocalString("auth.refreshToken", "refresh-1")
        val controller = ExtensionController(platform, { vaultFixture("pw") }, FakeExtensionAuthHandler(platform), cipherFactory = TestCiphers::forPassword)
        controller.initialize()
        controller.unlock("pw")
        val ready = assertIs<ExtensionUiState.VaultReady>(controller.state.value)
        controller.openNote(ready.flatNotes.single())
        assertNotNull(platform.getSessionString("session.selectedPath"))

        controller.lock()

        assertIs<ExtensionUiState.UnlockRequired>(controller.state.value)
        assertNull(platform.getSessionString("session.selectedPath"))
        assertNull(platform.getSessionString("session.cipherKeyHex"))
        assertEquals("refresh-1", platform.getLocalString("auth.refreshToken"))
        assertNotNull(platform.getLocalString("vault.encryptedEntries"))
    }

    @Test
    fun searchQueryFiltersNotesAndToggleFolderExpandsIt() = runTest {
        val platform = FakeExtensionPlatform()
        platform.seedAuthenticatedAccount(Account(email = "u@example.com", displayName = null))
        val controller = ExtensionController(platform, { vaultFixture("pw") }, FakeExtensionAuthHandler(platform), cipherFactory = TestCiphers::forPassword)
        controller.initialize()
        controller.unlock("pw")

        controller.updateSearchQuery("нет такой")
        assertTrue(assertIs<ExtensionUiState.VaultReady>(controller.state.value).filteredNotes.isEmpty())
        controller.updateSearchQuery("")
        assertEquals(1, assertIs<ExtensionUiState.VaultReady>(controller.state.value).filteredNotes.size)

        // По умолчанию все папки свернуты (expandedFolders пуст)
        assertTrue(assertIs<ExtensionUiState.VaultReady>(controller.state.value).expandedFolders.isEmpty())
        controller.toggleFolder("Папка")
        assertEquals(setOf("Папка"), assertIs<ExtensionUiState.VaultReady>(controller.state.value).expandedFolders)
        controller.toggleFolder("Папка")
        assertTrue(assertIs<ExtensionUiState.VaultReady>(controller.state.value).expandedFolders.isEmpty())
    }

    @Test
    fun openNoteExpandsParentFolders() = runTest {
        val cipher = TestCiphers.forPassword("pw")
        val rawRemote = InMemoryRemoteStorage()
        rawRemote.write(
            RemotePath(CanaryVerifier.CANARY_FILENAME),
            cipher.encrypt(Buffer().apply { writeString("""{"magic":"mimir-vault-canary","version":1}""") }),
        )
        val noteEncPath = RemotePath.of(listOf("Работа", "Проекты", "План.md").map(cipher::encryptName))
        rawRemote.write(
            noteEncPath,
            cipher.encrypt(Buffer().apply { writeString("# План") }),
        )
        val platform = FakeExtensionPlatform()
        platform.seedAuthenticatedAccount(Account(email = "u@example.com", displayName = null))
        val controller = ExtensionController(platform, { rawRemote }, FakeExtensionAuthHandler(platform), cipherFactory = TestCiphers::forPassword)
        controller.initialize()
        controller.unlock("pw")

        val ready = assertIs<ExtensionUiState.VaultReady>(controller.state.value)
        assertTrue(ready.expandedFolders.isEmpty())

        controller.openNote(ready.flatNotes.single())
        val updated = assertIs<ExtensionUiState.VaultReady>(controller.state.value)
        assertEquals(setOf("Работа", "Работа/Проекты"), updated.expandedFolders)
    }

    private class CountingChangeFeedStorage(
        private val inner: InMemoryRemoteStorage,
        var latestToken: String = "tok-1",
        var changesToReturn: List<RemoteChange> = emptyList(),
    ) : ChangeFeedStorage, RemoteStorage by inner {
        var listAllCalls = 0
        var getLatestTokenCalls = 0
        var getChangesCalls = 0

        override suspend fun getLatestChangeToken(): String {
            getLatestTokenCalls++
            return latestToken
        }

        override suspend fun getChanges(sinceToken: String): RemoteChangesResult {
            getChangesCalls++
            return RemoteChangesResult(changes = changesToReturn, nextToken = latestToken)
        }

        override suspend fun listAll(
            root: RemotePath,
            onProgress: (folders: Int, files: Int) -> Unit,
        ): List<RemoteEntry> {
            listAllCalls++
            return inner.listAll(root, onProgress)
        }
    }

    @Test
    fun refreshVaultSkipsListAllWhenCachedChangeTokenReturnsEmptyChangesFromChangeFeedStorage() = runTest {
        val raw = vaultFixture("pw")
        val feed = CountingChangeFeedStorage(raw, latestToken = "tok-1")
        val platform = FakeExtensionPlatform()
        platform.seedAuthenticatedAccount(Account(email = "u@example.com", displayName = null))
        val controller = ExtensionController(platform, { feed }, FakeExtensionAuthHandler(platform), cipherFactory = TestCiphers::forPassword)

        controller.initialize()
        controller.unlock("pw")
        assertIs<ExtensionUiState.VaultReady>(controller.state.value)
        assertEquals(1, feed.listAllCalls)
        assertEquals(0, feed.getChangesCalls)

        feed.latestToken = "tok-2"
        feed.changesToReturn = emptyList()
        controller.refreshVault()

        assertEquals(1, feed.getChangesCalls)
        assertEquals(1, feed.listAllCalls)
        assertIs<ExtensionUiState.VaultReady>(controller.state.value)

        feed.latestToken = "tok-3"
        feed.changesToReturn = listOf(RemoteChange(RemotePath("changed"), isDeleted = false))
        controller.refreshVault()

        assertEquals(2, feed.getChangesCalls)
        assertEquals(2, feed.listAllCalls)
    }
}
