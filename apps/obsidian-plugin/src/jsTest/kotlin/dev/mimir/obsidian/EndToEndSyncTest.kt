package dev.mimir.obsidian

import dev.mimir.core.CanaryVerifier
import dev.mimir.core.EncryptedRemoteStorage
import dev.mimir.core.InMemoryRemoteStorage
import dev.mimir.core.RcloneCipher
import dev.mimir.core.RemotePath
import dev.mimir.core.SyncAbortedException
import dev.mimir.core.fs.EchoFilter
import dev.mimir.core.sync.DefaultConflictResolver
import dev.mimir.core.sync.DefaultSyncEngine
import dev.mimir.core.testing.TestCiphers
import dev.mimir.core.testing.TestClock
import dev.mimir.obsidian.fakes.FakeVaultFs
import dev.mimir.obsidian.platform.VaultEvent
import dev.mimir.obsidian.state.ObsidianSyncStateStore
import dev.mimir.obsidian.vault.ObsidianLocalVault
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class EndToEndSyncTest {

    private class Device(
        val name: String,
        val cipher: RcloneCipher,
        val remote: InMemoryRemoteStorage,
        val clock: TestClock,
    ) {
        val fs = FakeVaultFs()
        val events = MutableSharedFlow<VaultEvent>(extraBufferCapacity = 50)
        val echo = EchoFilter(ttl = 5.seconds, clock = clock)
        val vault = ObsidianLocalVault(
            fs = fs,
            resources = { path -> Buffer().apply { write(fs.readBinary(path)) } },
            configDir = ".obsidian",
            events = events,
            echo = echo,
            clock = clock,
        )
        val stateStore = ObsidianSyncStateStore(fs, ".obsidian")
        val encryptedRemote = EncryptedRemoteStorage(
            inner = remote,
            cipher = cipher,
            reservedRootNames = setOf(CanaryVerifier.CANARY_FILENAME),
        )
        val engine = DefaultSyncEngine(
            localVault = vault,
            remoteStorage = encryptedRemote,
            stateStore = stateStore,
            conflictResolver = DefaultConflictResolver(clock = clock),
            clock = clock,
            parallelism = 2,
        )
    }

    @Test
    fun roundTripSyncBetweenTwoDevicesWithConflictAndAbortProtection() = runTest {
        val clock = TestClock(Instant.fromEpochMilliseconds(1_000_000_000_000L))
        val password = "end-to-end-test-password"
        val cipher = TestCiphers.forPassword(password)
        val remote = InMemoryRemoteStorage()

        val devA = Device("A", cipher, remote, clock)
        val devB = Device("B", cipher, remote, clock)

        // 1. A создаёт notes/hello.md -> синхронизирует
        devA.vault.write("notes/hello.md", Buffer().apply { write("Hello from A".encodeToByteArray()) })
        devA.engine.syncNow()

        // Проверяем, что в remote появились зашифрованные файлы
        assertTrue(remote.list(RemotePath("")).isNotEmpty())

        // 2. B синхронизирует -> notes/hello.md появляется в B с тем же содержимым
        clock.advanceBy(1.seconds)
        devB.engine.syncNow()
        val entryB = devB.vault.getEntry("notes/hello.md")
        assertNotNull(entryB)
        val contentB = devB.vault.read(entryB.id).readString()
        assertEquals("Hello from A", contentB)

        // 3. B редактирует notes/hello.md -> синхронизирует
        clock.advanceBy(1.seconds)
        devB.vault.write("notes/hello.md", Buffer().apply { write("Edited by B".encodeToByteArray()) })
        devB.engine.syncNow()

        // 4. A синхронизирует -> видит правку
        clock.advanceBy(1.seconds)
        devA.engine.syncNow()
        val entryA = devA.vault.getEntry("notes/hello.md")
        assertNotNull(entryA)
        val contentA = devA.vault.read(entryA.id).readString()
        assertEquals("Edited by B", contentA)

        // 5. Одновременное изменение на A и B -> синхронизация A, затем B
        clock.advanceBy(1.seconds)
        devA.vault.write("notes/hello.md", Buffer().apply { write("Conflict edit A".encodeToByteArray()) })
        devB.vault.write("notes/hello.md", Buffer().apply { write("Conflict edit B".encodeToByteArray()) })

        // A синхронизирует первым
        clock.advanceBy(1.seconds)
        devA.engine.syncNow()

        // B синхронизирует вторым -> конфликт
        clock.advanceBy(1.seconds)
        devB.engine.syncNow()

        // В B основной файл обновлён версией A, а правка B сохранена как копия конфликта
        val updatedEntryB = devB.vault.getEntry("notes/hello.md")
        assertNotNull(updatedEntryB)
        val updatedB = devB.vault.read(updatedEntryB.id).readString()
        assertEquals("Conflict edit A", updatedB)

        val filesInB = devB.vault.list()
        val conflictEntry = filesInB.firstOrNull { it.relativePath.contains("conflicted copy") }
        assertNotNull(conflictEntry, "Conflict copy must be preserved in vault B")
        val conflictContent = devB.vault.read(conflictEntry.id).readString()
        assertEquals("Conflict edit B", conflictContent)

        // 6. Очистка remote при непустом индексе -> SyncAbortedException, файлы B на месте
        // Удаляем все файлы на remote
        val allRemoteFiles = remote.listAll(RemotePath(""))
        for (f in allRemoteFiles) {
            remote.delete(f.path)
        }

        // Запуск синхронизации B -> прерывание без удаления локальных файлов
        assertFailsWith<SyncAbortedException> {
            devB.engine.syncNow()
        }

        // Локальные файлы B остались нетронутыми
        assertEquals("Conflict edit A", devB.vault.read(updatedEntryB.id).readString())
        assertEquals("Conflict edit B", devB.vault.read(conflictEntry.id).readString())
    }
}
