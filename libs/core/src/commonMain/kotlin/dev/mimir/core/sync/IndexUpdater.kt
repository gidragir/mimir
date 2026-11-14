package dev.mimir.core.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * Текущий [SyncIndex] прогона поверх [SyncStateStore] (спека sync-io §6): изменения из параллельных
 * воркеров применяются под [Mutex], индекс сохраняется после каждых [saveEvery] изменений и в [flush].
 * Если процесс упал между сохранениями, у путей просто нет записей — следующий прогон даст `Reconcile`.
 */
class IndexUpdater(
    private val store: SyncStateStore,
    initial: SyncIndex,
    private val saveEvery: Int = 100,
) {
    init {
        require(saveEvery >= 1) { "saveEvery must be positive, got $saveEvery" }
    }

    private val mutex = Mutex()

    @Volatile
    private var index: SyncIndex = initial
    private var unsaved = 0

    /** Снимок индекса на сейчас (неизменяемое значение). */
    val current: SyncIndex get() = index

    suspend fun update(transform: (SyncIndex) -> SyncIndex): Unit = mutex.withLock {
        index = transform(index)
        unsaved++
        if (unsaved >= saveEvery) saveLocked()
    }

    /** Применяет пакет метаданных ([SyncAction.UpdateMetadata], [SyncAction.ForgetRecord]) за одно копирование и одно сохранение. */
    suspend fun updateAll(actions: List<SyncAction>): Unit = mutex.withLock {
        if (actions.isEmpty()) return@withLock
        val put = mutableListOf<FileSyncRecord>()
        val remove = mutableListOf<String>()
        for (action in actions) {
            when (action) {
                is SyncAction.UpdateMetadata -> put += action.record
                is SyncAction.ForgetRecord -> remove += action.path
                else -> Unit
            }
        }
        if (put.isEmpty() && remove.isEmpty()) return@withLock
        index = index.withChanges(put = put, remove = remove)
        unsaved += put.size + remove.size
        if (unsaved >= saveEvery) saveLocked()
    }

    /** Сохраняет индекс, только если есть несохранённые изменения. */
    suspend fun flush(): Unit = mutex.withLock {
        if (unsaved > 0) saveLocked()
    }

    private suspend fun saveLocked() {
        store.save(index)
        unsaved = 0
    }
}
