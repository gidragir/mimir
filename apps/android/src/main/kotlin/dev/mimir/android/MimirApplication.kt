package dev.mimir.android

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import androidx.work.WorkManager
import dev.mimir.android.notify.StatusNotifier
import dev.mimir.android.notify.notificationFor
import dev.mimir.android.platform.AndroidNetworkState
import dev.mimir.android.work.MimirWorkerFactory
import dev.mimir.android.work.SyncWorkScheduler
import dev.mimir.core.NetworkKind
import dev.mimir.core.SyncPhase
import dev.mimir.core.SyncStatus
import dev.mimir.runtime.MimirRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Наблюдатель за жизненным циклом процесса приложения [ProcessLifecycleOwner].
 */
class MimirAppLifecycleObserver(
    private val runtime: () -> MimirRuntime,
    private val workScheduler: () -> SyncWorkScheduler,
    private val scope: CoroutineScope,
) : DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) {
        scope.launch {
            try {
                val rt = runtime()
                if (!rt.isReady) {
                    rt.initialize()
                }
                rt.setInteractive(true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Ошибки инициализации уже отражены в rt.status; не роняем процесс.
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        scope.launch {
            val rt = runtime()
            val needsBackgroundSync = rt.setInteractive(false)
            if (needsBackgroundSync && rt.vaultPath != null) {
                workScheduler().scheduleExpedited()
            }
        }
    }
}

/**
 * Точка входа приложения Android (Composition root).
 * Лениво собирает [MimirRuntime], настраивает WorkManager через [Configuration.Provider]
 * и отслеживает жизненный цикл процесса через [ProcessLifecycleOwner].
 */
class MimirApplication : Application(), Configuration.Provider {

    val runtime: MimirRuntime by lazy { AndroidRuntimeFactory.create(this) }

    val workScheduler: SyncWorkScheduler by lazy {
        SyncWorkScheduler(WorkManager.getInstance(this)) { false }
    }

    val statusNotifier: StatusNotifier by lazy { StatusNotifier(this) }

    private val networkState: AndroidNetworkState by lazy { AndroidNetworkState(this) }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(MimirWorkerFactory(runtime = { runtime }, scheduler = { workScheduler }))
            .build()

    override fun onCreate() {
        super.onCreate()

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            MimirAppLifecycleObserver(
                runtime = { runtime },
                workScheduler = { workScheduler },
                scope = applicationScope,
            ),
        )

        if (runtime.vaultPath != null) {
            workScheduler.scheduleVaultTrigger()
            workScheduler.schedulePeriodic()
        }

        observeSyncForNotifications()
    }

    private fun observeSyncForNotifications() {
        applicationScope.launch {
            runtime.vaultPathFlow.collect { path ->
                if (path != null) {
                    workScheduler.scheduleVaultTrigger()
                    workScheduler.schedulePeriodic()
                }
            }
        }
        applicationScope.launch {
            networkState.current.collect { kind ->
                if (kind != NetworkKind.NONE && runtime.status.value == SyncStatus.Offline && runtime.isReady) {
                    runCatching { runtime.triggerSync() }
                }
            }
        }
        applicationScope.launch {
            combine(
                runtime.status,
                runtime.progress,
                runtime.conflictsFlow,
                runtime.pendingLocalChanges,
            ) { status, progress, conflicts, pending ->
                NotificationKey(status, progress?.phase == SyncPhase.Finished, conflicts.size, pending)
            }.distinctUntilChanged().collect { key ->
                if (key.status !is SyncStatus.Syncing || key.finished) {
                    updateNotification()
                }
            }
        }
    }

    private data class NotificationKey(
        val status: SyncStatus,
        val finished: Boolean,
        val conflicts: Int,
        val pending: Boolean,
    )

    fun updateNotification() {
        val status = runtime.status.value
        if (status is SyncStatus.Syncing) return
        if (status is SyncStatus.NotConfigured) {
            statusNotifier.cancelAll()
            return
        }
        val content = notificationFor(
            status = status,
            conflicts = runtime.conflictsFlow.value.size,
            pendingLocal = runtime.pendingLocalChanges.value,
            lastSync = runtime.lastSyncTime,
        )
        if (content != null) {
            statusNotifier.show(content)
        }
    }
}
