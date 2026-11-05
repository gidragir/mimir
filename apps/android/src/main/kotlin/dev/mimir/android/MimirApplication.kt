package dev.mimir.android

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import androidx.work.WorkManager
import dev.mimir.android.notify.StatusNotifier
import dev.mimir.android.notify.notificationFor
import dev.mimir.android.work.MimirWorkerFactory
import dev.mimir.android.work.SyncWorkScheduler
import dev.mimir.core.SyncPhase
import dev.mimir.core.SyncStatus
import dev.mimir.runtime.MimirRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
            val rt = runtime()
            if (!rt.isReady) {
                rt.initialize()
            }
            rt.setInteractive(true)
            rt.syncOnce()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        scope.launch {
            val rt = runtime()
            rt.setInteractive(false)
            if (rt.pendingLocalChanges.value) {
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
            runtime.status.collect { status ->
                if (status !is SyncStatus.Syncing) {
                    updateNotification()
                }
            }
        }
        applicationScope.launch {
            runtime.progress.collect { progress ->
                if (progress?.phase == SyncPhase.Finished) {
                    updateNotification()
                }
            }
        }
        applicationScope.launch {
            runtime.conflictsFlow.collect {
                updateNotification()
            }
        }
        applicationScope.launch {
            runtime.pendingLocalChanges.collect {
                updateNotification()
            }
        }
    }

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
