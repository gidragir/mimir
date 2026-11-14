package dev.mimir.core.testing

import dev.mimir.core.LocalEntry
import dev.mimir.core.LocalVault
import dev.mimir.core.VaultFileId
import kotlinx.io.Source
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/** Декоратор [LocalVault] для тестов бюджета IO: считает операции записи и чтения потокобезопасно. */
@OptIn(ExperimentalAtomicApi::class)
class CountingLocalVault(
    private val inner: LocalVault = InMemoryLocalVault(),
) : LocalVault by inner {
    private val writeCount = AtomicInt(0)
    private val readCount = AtomicInt(0)
    private val listCount = AtomicInt(0)

    val writes: Int get() = writeCount.load()
    val reads: Int get() = readCount.load()
    val lists: Int get() = listCount.load()

    override suspend fun list(): List<LocalEntry> {
        listCount.incrementAndFetch()
        return inner.list()
    }

    override suspend fun read(id: VaultFileId): Source {
        readCount.incrementAndFetch()
        return inner.read(id)
    }

    override suspend fun write(relativePath: String, data: Source): LocalEntry {
        writeCount.incrementAndFetch()
        return inner.write(relativePath, data)
    }

    override suspend fun write(id: VaultFileId, data: Source) {
        writeCount.incrementAndFetch()
        inner.write(id, data)
    }

    fun reset() {
        writeCount.store(0)
        readCount.store(0)
        listCount.store(0)
    }
}
