package dev.mimir.android

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import androidx.work.WorkManager
import dev.mimir.android.work.MimirWorkerFactory
import dev.mimir.android.work.SyncWorkScheduler
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
    }
}

