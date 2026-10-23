package dev.mimir.core

import dev.mimir.core.sync.ConflictDetails
import dev.mimir.core.sync.ConflictResolver
import dev.mimir.core.sync.DefaultConflictResolver
import dev.mimir.core.sync.FileSyncRecord
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

class ConflictResolverTest {

    private val fixedInstant = Instant.parse("2026-10-23T23:15:30Z")
    private val testClock = object : Clock {
        override fun now(): Instant = fixedInstant
    }

    private fun bufferOf(text: String) = Buffer().also { it.write(text.encodeToByteArray()) }

    @Test
    fun generateConflictPathFormatsCorrectly() {
        // Обычный файл с расширением
        assertEquals(
            "folder/note (conflicted copy 2026-10-23-231530).md",
            ConflictResolver.generateConflictPath("folder/note.md", fixedInstant)
        )

        // Файл в корне
        assertEquals(
            "note (conflicted copy 2026-10-23-231530).md",
            ConflictResolver.generateConflictPath("note.md", fixedInstant)
        )

        // Файл без расширения
        assertEquals(
            "LICENSE (conflicted copy 2026-10-23-231530)",
            ConflictResolver.generateConflictPath("LICENSE", fixedInstant)
        )

        // Вложенный путь без расширения
        assertEquals(
            "docs/README (conflicted copy 2026-10-23-231530)",
            ConflictResolver.generateConflictPath("docs/README", fixedInstant)
        )

        // Скрытый файл
        assertEquals(
            ".hidden (conflicted copy 2026-10-23-231530)",
            ConflictResolver.generateConflictPath(".hidden", fixedInstant)
        )

        // Несколько точек
        assertEquals(
            "archive.tar (conflicted copy 2026-10-23-231530).gz",
            ConflictResolver.generateConflictPath("archive.tar.gz", fixedInstant)
        )
    }

    @Test
    fun defaultConflictResolverPreservesBothVersions() = runTest {
        val resolver = DefaultConflictResolver(clock = testClock)

        val localEntry = LocalEntry(
            id = VaultFileId("local-1"),
            relativePath = "notes/todo.md",
            size = 15L,
            modifiedAt = fixedInstant,
        )
        val remoteEntry = RemoteEntry(
            path = RemotePath("notes/todo.md"),
            size = 20L,
            modifiedAt = fixedInstant,
            revision = "rev-2",
            isDirectory = false,
        )
        val baseRecord = FileSyncRecord(
            relativePath = "notes/todo.md",
            localId = VaultFileId("local-1"),
            baseHash = "base-hash",
            localSize = 10L,
            localModifiedAt = fixedInstant,
            remotePath = RemotePath("notes/todo.md"),
            remoteRevision = "rev-1",
            remoteModifiedAt = fixedInstant,
            remoteSize = 10L,
        )

        val details = ConflictDetails(
            path = "notes/todo.md",
            localEntry = localEntry,
            remoteEntry = remoteEntry,
            baseRecord = baseRecord,
        )

        val localContent = "Local changes content"
        val remoteContent = "Remote conflicting content"

        val outcome = resolver.resolveConflict(
            details = details,
            localSource = bufferOf(localContent),
            remoteSource = bufferOf(remoteContent),
        )

        assertEquals("notes/todo.md", outcome.mainPath)
        assertEquals("notes/todo (conflicted copy 2026-10-23-231530).md", outcome.conflictPath)

        val conflictBytes = outcome.conflictSource.readByteArray()
        assertContentEquals(localContent.encodeToByteArray(), conflictBytes)
    }
}
