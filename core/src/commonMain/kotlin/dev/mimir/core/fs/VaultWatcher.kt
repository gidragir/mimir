package dev.mimir.core.fs

import dev.mimir.core.VaultChange
import kotlinx.coroutines.flow.Flow

interface VaultWatcher {
    /** Холодный поток локальных изменений; наблюдение идёт, пока поток собирается. */
    fun start(): Flow<VaultChange>
    fun stop()

    /** Запись сделана самим движком синхронизации: ответное событие подавляется (echo suppression). */
    fun markWrittenBySync(relativePath: String)
}
