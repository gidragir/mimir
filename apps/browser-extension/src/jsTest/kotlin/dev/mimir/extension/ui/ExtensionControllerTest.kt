package dev.mimir.extension.ui

import dev.mimir.core.Account
import dev.mimir.core.CanaryVerifier
import dev.mimir.core.RcloneCipher
import dev.mimir.core.RemotePath
import dev.mimir.core.CountingRemoteStorage
import dev.mimir.core.InMemoryRemoteStorage
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
        val cipher = RcloneCipher.create("master-pass")
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
        val cipher = RcloneCipher.create(password)
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
        val controller = ExtensionController(platform, { counting }, FakeExtensionAuthHandler(platform))
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
        val controller = ExtensionController(platform, { vaultFixture("pw") }, FakeExtensionAuthHandler(platform))
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
    fun searchQueryFiltersNotesAndToggleFolderCollapsesIt() = runTest {
        val platform = FakeExtensionPlatform()
        platform.seedAuthenticatedAccount(Account(email = "u@example.com", displayName = null))
        val controller = ExtensionController(platform, { vaultFixture("pw") }, FakeExtensionAuthHandler(platform))
        controller.initialize()
        controller.unlock("pw")

        controller.updateSearchQuery("нет такой")
        assertTrue(assertIs<ExtensionUiState.VaultReady>(controller.state.value).filteredNotes.isEmpty())
        controller.updateSearchQuery("")
        assertEquals(1, assertIs<ExtensionUiState.VaultReady>(controller.state.value).filteredNotes.size)

        controller.toggleFolder("Папка")
        assertEquals(setOf("Папка"), assertIs<ExtensionUiState.VaultReady>(controller.state.value).collapsedFolders)
        controller.toggleFolder("Папка")
        assertTrue(assertIs<ExtensionUiState.VaultReady>(controller.state.value).collapsedFolders.isEmpty())
    }
}
