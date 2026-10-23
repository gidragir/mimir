package dev.mimir.core

import dev.mimir.core.sync.DefaultConflictResolver
import dev.mimir.core.sync.DefaultSyncEngine
import dev.mimir.core.sync.InMemorySyncStateStore
import dev.mimir.core.testing.InMemoryLocalVault
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SyncEngineIntegrationTest {

    private fun bufferOf(text: String): Buffer =
        Buffer().also { it.write(text.encodeToByteArray()) }

    private val cipher = RcloneCipher("mimir-secure-pass", "salt2")

    @Test
    fun endToEnd_encryptedStorage_roundTrip_and_structure() = runTest {
        val rawRemote = InMemoryRemoteStorage()
        val encryptedRemote = EncryptedRemoteStorage(rawRemote, cipher)

        val vaultA = InMemoryLocalVault()
        val storeA = InMemorySyncStateStore()
        val engineA = DefaultSyncEngine(
            localVault = vaultA,
            remoteStorage = encryptedRemote,
            stateStore = storeA,
        )

        val notePath = "Health/Mom tests.md"
        val noteContent = "# Medical records for Mom\nConfidential test results."
        vaultA.write(notePath, bufferOf(noteContent))

        engineA.syncNow()
        assertEquals(SyncStatus.Idle, engineA.status.value)

        // 1. Проверяем сырое хранилище: пути и контент ОБЯЗАНЫ быть зашифрованы
        val rawEntries = rawRemote.list(RemotePath(""))
        assertTrue(rawEntries.isNotEmpty())
        for (entry in rawEntries) {
            assertFalse(entry.path.value.contains("Health"))
            assertFalse(entry.path.value.contains("Mom"))
        }

        // 2. Клиент B на чистом vault стягивает из зашифрованного хранилища
        val vaultB = InMemoryLocalVault()
        val storeB = InMemorySyncStateStore()
        val engineB = DefaultSyncEngine(
            localVault = vaultB,
            remoteStorage = encryptedRemote,
            stateStore = storeB,
        )

        engineB.syncNow()
        assertEquals(SyncStatus.Idle, engineB.status.value)

        val downloadedEntry = vaultB.getEntry(notePath)
        assertNotNull(downloadedEntry)
        val downloadedBytes = vaultB.read(downloadedEntry.id).readByteArray()
        assertContentEquals(noteContent.encodeToByteArray(), downloadedBytes)
    }

    @Test
    fun endToEnd_conflictResolution_preservesBothVersionsEncrypted() = runTest {
        val rawRemote = InMemoryRemoteStorage()
        val encryptedRemote = EncryptedRemoteStorage(rawRemote, cipher)

        val vaultA = InMemoryLocalVault()
        val storeA = InMemorySyncStateStore()
        val engineA = DefaultSyncEngine(vaultA, encryptedRemote, storeA)

        val vaultB = InMemoryLocalVault()
        val storeB = InMemorySyncStateStore()
        val engineB = DefaultSyncEngine(vaultB, encryptedRemote, storeB)

        // Начальная синхронизация общего файла
        val path = "Notes/Recipe.md"
        vaultA.write(path, bufferOf("Initial recipe"))
        engineA.syncNow()
        engineB.syncNow()

        // Одновременное независимое редактирование
        vaultA.write(path, bufferOf("Recipe with cinnamon (edit A)"))
        engineA.syncNow() // A успел отправить в облако

        vaultB.write(path, bufferOf("Recipe with vanilla (edit B)"))
        engineB.syncNow() // B сталкивается с конфликтом при синхронизации!

        // Проверяем vault B: основной файл содержит версию A, конфликтная копия — версию B
        val listB = vaultB.list()
        assertEquals(2, listB.size)

        val mainEntry = vaultB.getEntry(path)
        assertNotNull(mainEntry)
        assertContentEquals(
            "Recipe with cinnamon (edit A)".encodeToByteArray(),
            vaultB.read(mainEntry.id).readByteArray()
        )

        val conflictEntry = listB.first { it.relativePath != path }
        assertTrue(conflictEntry.relativePath.startsWith("Notes/Recipe (conflicted copy"))
        assertContentEquals(
            "Recipe with vanilla (edit B)".encodeToByteArray(),
            vaultB.read(conflictEntry.id).readByteArray()
        )

        // Проверяем, что конфликтная копия тоже залита в encryptedRemote
        val remoteFiles = encryptedRemote.list(RemotePath("Notes"))
        assertEquals(2, remoteFiles.size)
    }

    @Test
    fun endToEnd_failureRecovery_resumesWithoutDuplication() = runTest {
        val failingRemote = object : RemoteStorage {
            val delegate = InMemoryRemoteStorage()
            var writeCount = 0
            var failOnThirdWrite = false

            override suspend fun list(dir: RemotePath) = delegate.list(dir)
            override suspend fun stat(path: RemotePath) = delegate.stat(path)
            override suspend fun read(path: RemotePath) = delegate.read(path)
            override suspend fun delete(path: RemotePath) = delegate.delete(path)
            override suspend fun write(path: RemotePath, data: Source): RemoteEntry {
                writeCount++
                if (failOnThirdWrite && writeCount == 3) {
                    throw IOException("Network dropped on file 3")
                }
                return delegate.write(path, data)
            }
        }

        val encryptedRemote = EncryptedRemoteStorage(failingRemote, cipher)
        val vault = InMemoryLocalVault()
        val store = InMemorySyncStateStore()
        val engine = DefaultSyncEngine(vault, encryptedRemote, store)

        // Добавляем 5 файлов
        for (i in 1..5) {
            vault.write("file-$i.md", bufferOf("content $i"))
        }

        failingRemote.failOnThirdWrite = true

        // Первая синхронизация прерывается на 3-м файле
        assertFailsWith<IOException> {
            engine.syncNow()
        }
        val status1 = engine.status.value
        assertTrue(status1 is SyncStatus.Error)

        // Индекс должен содержать ровно 2 успешно завершенных файла
        val indexMidway = store.load()
        assertEquals(2, indexMidway.records.size)

        // Устраняем сбой сети и возобновляем синхронизацию
        failingRemote.failOnThirdWrite = false
        engine.syncNow()

        assertEquals(SyncStatus.Idle, engine.status.value)
        val finalIndex = store.load()
        assertEquals(5, finalIndex.records.size)
    }
}
