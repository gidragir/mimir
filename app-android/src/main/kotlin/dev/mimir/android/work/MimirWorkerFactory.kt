package dev.mimir.android.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import dev.mimir.runtime.MimirRuntime

/**
 * Фабрика Worker'ов WorkManager, внедряющая зависимости в [MimirSyncWorker]
 * и последующие Worker'ы (Task 9).
 */
class MimirWorkerFactory(
    private val runtime: () -> MimirRuntime,
    private val scheduler: () -> SyncWorkScheduler,
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        MimirSyncWorker::class.java.name -> MimirSyncWorker(
            appContext,
            workerParameters,
            runner = SyncRunner {
                val rt = runtime()
                if (!rt.isReady) {
                    rt.initialize()
                }
                rt.syncOnce()
            },
        )
        else -> null
    }
}
