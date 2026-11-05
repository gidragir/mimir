package dev.mimir.core.fs

import dev.mimir.core.VaultChange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart

interface VaultWatcher {
    /** Холодный поток локальных изменений; наблюдение идёт, пока поток собирается. */
    fun start(): Flow<VaultChange>

    /**
     * Как [start], но [onWatching] вызывается, когда наблюдение уже идёт: разовый скан после него не пропустит
     * изменений. Реализация по умолчанию вызывает его перед подпиской — без гарантии для изменений в этот момент.
     */
    fun start(onWatching: suspend () -> Unit): Flow<VaultChange> = start().onStart { onWatching() }

    fun stop()

    /** Запись сделана самим движком синхронизации: ответное событие подавляется (echo suppression). */
    fun markWrittenBySync(relativePath: String)
}
