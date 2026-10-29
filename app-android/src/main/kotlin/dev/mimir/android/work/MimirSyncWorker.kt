package dev.mimir.android.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

/**
 * Фоновщик WorkManager для синхронизации без долгоживущих foreground-сервисов
 * с учетом ограничений фоновой работы Android 15/16.
 */
class MimirSyncWorker(
    appContext: Context,
    params: WorkerParameters,
    private val runner: SyncRunner,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            runner.syncOnce()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
