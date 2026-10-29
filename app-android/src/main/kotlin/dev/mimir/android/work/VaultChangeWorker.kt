package dev.mimir.android.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.mimir.core.rules.SyncRules
import kotlinx.coroutines.CancellationException

/**
 * Контекст vault для проверки релевантности изменений MediaStore.
 */
data class VaultContext(
    val root: String,
    val rules: SyncRules,
    val writtenBySync: Set<String>,
    val storageAccess: Boolean,
)

/**
 * Фоновщик WorkManager, пробуждаемый триггером MediaStore при изменении файлов.
 * Первым делом перепланирует триггер, проверяет доступ к файлам и релевантность
 * изменившихся путей, после чего запускает синхронизацию.
 */
class VaultChangeWorker(
    appContext: Context,
    params: WorkerParameters,
    private val runner: SyncRunner,
    private val scheduler: SyncWorkScheduler,
    private val resolver: TriggeredPathResolver,
    private val vault: () -> VaultContext?,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        scheduler.scheduleVaultTrigger()

        val context = vault() ?: return Result.success()
        if (!context.storageAccess) {
            return Result.success()
        }

        val triggeredUris = triggeredContentUris
        if (triggeredUris.isEmpty()) {
            return Result.success()
        }

        val paths = resolver.paths(triggeredUris)
        val isRelevant = VaultChangeFilter.relevant(
            absolutePaths = paths,
            vaultRoot = context.root,
            rules = context.rules,
            writtenBySync = context.writtenBySync,
        )

        if (!isRelevant) {
            return Result.success()
        }

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
