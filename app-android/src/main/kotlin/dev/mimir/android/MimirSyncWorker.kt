package dev.mimir.android

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import dev.mimir.core.SyncEngine
import java.util.concurrent.TimeUnit

/**
 * Фоновщик WorkManager для синхронизации без долгоживущих foreground-сервисов
 * с учетом ограничений фоновой работы Android 15/16.
 */
class MimirSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val engine = syncEngineProvider?.invoke()
            ?: return Result.success()

        return try {
            engine.syncNow()
            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME_PERIODIC = "mimir-periodic-sync"
        const val WORK_NAME_EXPEDITED = "mimir-expedited-sync"

        /** Провайдер активного движка синхронизации. */
        var syncEngineProvider: (() -> SyncEngine?)? = null

        fun buildPeriodicRequest(
            repeatIntervalMinutes: Long = 15,
            requiresCharging: Boolean = false,
        ): PeriodicWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresCharging(requiresCharging)
                .build()

            return PeriodicWorkRequestBuilder<MimirSyncWorker>(repeatIntervalMinutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
        }

        fun buildExpeditedRequest(): OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            return OneTimeWorkRequestBuilder<MimirSyncWorker>()
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
        }
    }
}
