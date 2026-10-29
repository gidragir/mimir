package dev.mimir.android.work

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import dev.mimir.core.rules.SyncRules
import dev.mimir.runtime.MimirRuntime

/**
 * Фабрика Worker'ов WorkManager, внедряющая зависимости в [MimirSyncWorker]
 * и [VaultChangeWorker].
 */
class MimirWorkerFactory(
    private val runtime: () -> MimirRuntime,
    private val scheduler: () -> SyncWorkScheduler,
    private val resolverProvider: (Context) -> TriggeredPathResolver = { ContentResolverTriggeredPathResolver(it.contentResolver) },
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
        VaultChangeWorker::class.java.name -> VaultChangeWorker(
            appContext,
            workerParameters,
            runner = SyncRunner {
                val rt = runtime()
                if (!rt.isReady) {
                    rt.initialize()
                }
                rt.syncOnce()
            },
            scheduler = scheduler(),
            resolver = resolverProvider(appContext),
            vault = {
                val rt = runtime()
                val root = rt.vaultPath ?: return@VaultChangeWorker null
                val storageAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Environment.isExternalStorageManager()
                } else {
                    appContext.checkSelfPermission(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ) == PackageManager.PERMISSION_GRANTED
                }
                VaultContext(
                    root = root,
                    rules = SyncRules(),
                    writtenBySync = rt.recentlyWrittenBySync(),
                    storageAccess = storageAccess,
                )
            },
        )
        else -> null
    }
}
