package dev.mimir.android.work

/**
 * Интерфейс запуска синхронизации для Worker'ов WorkManager.
 * Изолирует Worker'ы от прямой зависимости на [dev.mimir.runtime.MimirRuntime].
 */
fun interface SyncRunner {
    suspend fun syncOnce(): Boolean
}
