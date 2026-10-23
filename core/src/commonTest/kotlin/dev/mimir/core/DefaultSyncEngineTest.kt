package dev.mimir.core

import dev.mimir.core.rules.SyncRules
import dev.mimir.core.sync.DefaultConflictResolver
import dev.mimir.core.sync.DefaultSyncEngine
import dev.mimir.core.sync.InMemorySyncStateStore
import dev.mimir.core.testing.InMemoryLocalVault
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DefaultSyncEngineTest {

    private fun bufferOf(text: String): Buffer =
        Buffer().also { it.write(text.encodeToByteArray()) }

    @Test
    fun initialSync_uploadsLocalFileToRemote() = runTest {
        val vault = InMemoryLocalVault()
        val remote = InMemoryRemoteStorage()
        val store = InMemorySyncStateStore()
        val engine = DefaultSyncEngine(
            localVault = vault,
            remoteStorage = remote,
            stateStore = store,
        )

        assertEquals(SyncStatus.Idle, engine.status.value)

        vault.write("Notes/Daily.md", bufferOf("# My Day"))
        engine.syncNow()

        assertEquals(SyncStatus.Idle, engine.status.value)

        // Проверяем, что файл появился в remote
        val remoteStat = remote.stat(RemotePath("Notes/Daily.md"))
        assertNotNull(remoteStat)
        val remoteData = remote.read(RemotePath("Notes/Daily.md")).readByteArray()
        assertContentEquals("# My Day".encodeToByteArray(), remoteData)

        // Проверяем, что индекс сохранен
        val index = store.load()
        val record = index.find("Notes/Daily.md")
        assertNotNull(record)
        assertEquals("Notes/Daily.md", record.relativePath)
    }

    @Test
    fun initialSync_downloadsRemoteFileToLocal() = runTest {
        val vault = InMemoryLocalVault()
        val remote = InMemoryRemoteStorage()
        val store = InMemorySyncStateStore()
        val engine = DefaultSyncEngine(
            localVault = vault,
            remoteStorage = remote,
            stateStore = store,
        )

        remote.write(RemotePath("Work/Tasks.md"), bufferOf("todo 1\ntodo 2"))
        engine.syncNow()

        val localEntry = vault.getEntry("Work/Tasks.md")
        assertNotNull(localEntry)
        val localBytes = vault.read(localEntry.id).readByteArray()
        assertContentEquals("todo 1\ntodo 2".encodeToByteArray(), localBytes)

        val record = store.load().find("Work/Tasks.md")
        assertNotNull(record)
    }

    @Test
    fun syncNow_concurrentCallsAreCoalesced() = runTest {
        val vault = InMemoryLocalVault()
        val remote = InMemoryRemoteStorage()
        val store = InMemorySyncStateStore()
        val engine = DefaultSyncEngine(
            localVault = vault,
            remoteStorage = remote,
            stateStore = store,
        )

        vault.write("a.md", bufferOf("A"))
        vault.write("b.md", bufferOf("B"))

        // Запуск двух параллельных вызовов syncNow()
        val sync1 = async { engine.syncNow() }
        val sync2 = async { engine.syncNow() }
        sync1.await()
        sync2.await()

        assertEquals(SyncStatus.Idle, engine.status.value)
        assertNotNull(remote.stat(RemotePath("a.md")))
        assertNotNull(remote.stat(RemotePath("b.md")))
    }

    @Test
    fun syncNow_setsErrorStatusOnFailure() = runTest {
        val vault = InMemoryLocalVault()
        val remote = InMemoryRemoteStorage()
        val store = InMemorySyncStateStore()
        val engine = DefaultSyncEngine(
            localVault = vault,
            remoteStorage = remote,
            stateStore = store,
        )

        vault.write("fail.md", bufferOf("data"))
        vault.throwOnRead = true

        assertFails {
            engine.syncNow()
        }

        val status = engine.status.value
        assertTrue(status is SyncStatus.Error)
        assertTrue(status.message.contains("Read failed"))
    }
}
