package dev.mimir.android.work

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Планировщик фоновых задач WorkManager для Mimir на Android.
 */
class SyncWorkScheduler(
    private val workManager: WorkManager,
    private val wifiOnly: () -> Boolean = { false },
) {
    fun schedulePeriodic() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly()) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<MimirSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun scheduleExpedited() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly()) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<MimirSyncWorker>()
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        workManager.enqueueUniqueWork(
            EXPEDITED,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun scheduleVaultTrigger() {
        // Зарезервировано для Task 9 (MediaStore ContentUriTrigger)
    }

    companion object {
        const val PERIODIC = "mimir-periodic-sync"
        const val EXPEDITED = "mimir-expedited-sync"
        const val VAULT_CHANGE = "mimir-vault-change"
    }
}
