package dev.mimir.extension.vault

import dev.mimir.core.RcloneCipher
import dev.mimir.core.RemoteEntry
import dev.mimir.core.RemotePath
import dev.mimir.drive.auth.GoogleClientKind
import dev.mimir.drive.auth.OAuthTokens
import dev.mimir.extension.platform.ExtensionTokenStorage
import dev.mimir.extension.platform.FakeExtensionPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class VaultTreeBuilderTest {
    @Test
    fun buildsSortedHierarchySkipsExcludedAndUnencryptedGarbage() = runTest {
        val cipher = RcloneCipher.create("test-pass")
        fun encPath(vararg segments: String) = RemotePath.of(segments.map(cipher::encryptName))

        val entries = listOf(
            RemoteEntry(encPath("Проекты", "Mimir", "Архитектура.md"), size = 120, modifiedAt = Instant.fromEpochSeconds(1700000100), revision = "1", isDirectory = false),
            RemoteEntry(encPath("Инбокс.md"), size = 50, modifiedAt = Instant.fromEpochSeconds(1700000200), revision = "2", isDirectory = false),
            RemoteEntry(encPath(".obsidian", "workspace.json"), size = 10, modifiedAt = Instant.fromEpochSeconds(1700000300), revision = "3", isDirectory = false),
            RemoteEntry(encPath(".trash", "Удаленная.md"), size = 10, modifiedAt = Instant.fromEpochSeconds(1700000400), revision = "4", isDirectory = false),
            RemoteEntry(RemotePath(".mimir_canary"), size = 42, modifiedAt = Instant.fromEpochSeconds(1700000000), revision = "5", isDirectory = false),
            RemoteEntry(RemotePath("not-valid-base32-ciphertext!!!"), size = 99, modifiedAt = Instant.fromEpochSeconds(1700000500), revision = "6", isDirectory = false),
        )

        val snapshot = VaultTreeBuilder.build(entries, cipher)
        assertEquals(2, snapshot.flatNotes.size)
        assertEquals(listOf("Проекты/Mimir/Архитектура.md", "Инбокс.md"), snapshot.flatNotes.map { it.relativePath })

        val rootFolder = snapshot.tree.first() as VaultTreeNode.Folder
        assertEquals("Проекты", rootFolder.name)
        val rootFile = snapshot.tree[1] as VaultTreeNode.File
        assertEquals("Инбокс.md", rootFile.name)
    }

    @Test
    fun filtersByNameAndPathAndResolvesWikilinks() = runTest {
        val cipher = RcloneCipher.create("test-pass")
        fun encPath(vararg segments: String) = RemotePath.of(segments.map(cipher::encryptName))

        val entries = listOf(
            RemoteEntry(encPath("Проекты", "Деплой ключей.md"), size = 100, modifiedAt = Instant.fromEpochSeconds(100), revision = "1", isDirectory = false),
            RemoteEntry(encPath("Личное", "Книги.md"), size = 80, modifiedAt = Instant.fromEpochSeconds(200), revision = "2", isDirectory = false),
        )
        val snapshot = VaultTreeBuilder.build(entries, cipher)

        val filtered = VaultTreeBuilder.filterNotes(snapshot.flatNotes, "прое")
        assertEquals(1, filtered.size)
        assertEquals("Проекты/Деплой ключей.md", filtered.single().relativePath)

        assertNotNull(VaultTreeBuilder.resolveWikilink(snapshot.flatNotes, "Деплой ключей"))
        assertNotNull(VaultTreeBuilder.resolveWikilink(snapshot.flatNotes, "Проекты/Деплой ключей|Мой алиас"))
        assertNull(VaultTreeBuilder.resolveWikilink(snapshot.flatNotes, "Несуществующая"))
    }

    @Test
    fun extensionTokenStorageSplitsAccessInSessionAndRefreshInLocal() = runTest {
        val platform = FakeExtensionPlatform()
        val storage = ExtensionTokenStorage(platform)
        val tokens = OAuthTokens(
            accessToken = "acc-1",
            refreshToken = "ref-1",
            expiresAtEpochSeconds = 999999L,
            scope = "drive.file",
            tokenType = "Bearer",
            clientKind = GoogleClientKind.Desktop,
        )
        storage.save(tokens)
        assertEquals("acc-1", storage.load()?.accessToken)
        assertEquals("ref-1", storage.load()?.refreshToken)

        platform.clearSession()
        val afterSessionClear = storage.load()
        assertNotNull(afterSessionClear)
        assertEquals("", afterSessionClear.accessToken)
        assertEquals(0L, afterSessionClear.expiresAtEpochSeconds)
        assertEquals("ref-1", afterSessionClear.refreshToken)
    }
}
