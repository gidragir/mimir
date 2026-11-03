package dev.mimir.runtime.update

import dev.mimir.core.InMemoryRemoteStorage
import dev.mimir.core.RemoteEntry
import dev.mimir.core.RemotePath
import dev.mimir.core.ChangeFeedStorage
import dev.mimir.core.SyncStatus
import dev.mimir.core.update.DownloadedAsset
import dev.mimir.core.update.SemVer
import dev.mimir.core.update.UpdateApplyResult
import dev.mimir.core.update.UpdateCheckResult
import dev.mimir.core.update.UpdateManager
import dev.mimir.core.update.UpdateState
import dev.mimir.runtime.MimirRuntime
import dev.mimir.runtime.signIn
import dev.mimir.runtime.testRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** Проводка обновлений в [MimirRuntime]; реальное время с короткими задержками, scope отменяется в finally. */
class RuntimeUpdateIntegrationTest {
    private class FakeManager : UpdateManager {
        override val state: StateFlow<UpdateState> = MutableStateFlow(UpdateState.Idle())
        val checks = AtomicInteger()

        override suspend fun checkForUpdates(force: Boolean): UpdateCheckResult {
            checks.incrementAndGet()
            return UpdateCheckResult.NoUpdate(SemVer.parse("1.0.0"))
        }

        override suspend fun downloadUpdate(): Result<DownloadedAsset> = error("unused")
        override suspend fun applyUpdate(): UpdateApplyResult = error("unused")
        override fun dismiss() = Unit
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val dataDir = Files.createTempDirectory("mimir-upd-data")
    private val vaultDir = Files.createTempDirectory("mimir-upd-vault")
    private var probe: (() -> Boolean)? = null

    private fun runtime(
        manager: UpdateManager?,
        remote: ChangeFeedStorage? = null,
        interval: kotlin.time.Duration = 40.milliseconds,
    ): MimirRuntime = testRuntime(
        dataDir,
        vaultPath = vaultDir.toString().takeIf { remote != null },
        password = "secret".takeIf { remote != null },
        cloud = { _, _, _ -> remote },
        updateManager = manager,
        updateSchedulerFactory = { m, busy ->
            probe = busy
            UpdateScheduler(m, scope, busy, initialDelay = 20.milliseconds, interval = interval, busyRecheck = 20.milliseconds)
        },
    )

    private suspend fun eventually(block: () -> Boolean) = withTimeout(15_000) {
        while (!block()) delay(10)
    }

    private fun withRuntime(rt: MimirRuntime, block: suspend () -> Unit) = runBlocking {
        try {
            block()
        } finally {
            rt.close()
            scope.cancel()
            dataDir.toFile().deleteRecursively()
            vaultDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun defaultsToDisabledManagerWithoutScheduler() {
        val rt = runtime(null)
        withRuntime(rt) {
            assertSame(DisabledUpdateManager, rt.updateManager)
            assertTrue(rt.updateManager.checkForUpdates() is UpdateCheckResult.Error)
            rt.initialize()
            assertEquals(null, probe) // фабрика не вызывалась
        }
    }

    @Test
    fun initializeStartsSchedulerAndExposesManager() {
        val manager = FakeManager()
        val rt = runtime(manager)
        withRuntime(rt) {
            assertSame(manager, rt.updateManager)
            assertEquals(0, manager.checks.get())
            rt.initialize()
            eventually { manager.checks.get() >= 1 }
        }
    }

    @Test
    fun setInteractiveFalseAndCloseStopScheduler() {
        val manager = FakeManager()
        val rt = runtime(manager)
        withRuntime(rt) {
            rt.initialize()
            eventually { manager.checks.get() >= 2 }
            rt.setInteractive(false)
            delay(60.milliseconds) // допустим уже летящий тик
            val frozen = manager.checks.get()
            delay(200.milliseconds)
            assertEquals(frozen, manager.checks.get())

            rt.setInteractive(true)
            eventually { manager.checks.get() > frozen }
            rt.close()
            delay(60.milliseconds)
            val afterClose = manager.checks.get()
            delay(200.milliseconds)
            assertEquals(afterClose, manager.checks.get())
        }
    }

    @Test
    fun syncBusyProbeFollowsSyncingStatus() {
        val gate = CompletableDeferred<Unit>()
        val inner = InMemoryRemoteStorage()
        val blocking = object : ChangeFeedStorage by inner {
            override suspend fun listAll(root: RemotePath, onProgress: (folders: Int, files: Int) -> Unit): List<RemoteEntry> {
                gate.await() // только обход синхронизации; проверка canary в initialize() идёт через stat/read
                return inner.listAll(root, onProgress)
            }
        }
        val manager = FakeManager()
        // длинный интервал: во время Syncing проверка не должна запускаться
        val rt = runtime(manager, remote = blocking, interval = 10.minutes)
        withRuntime(rt) {
            try {
                signIn(dataDir)
                rt.initialize()
                eventually { rt.status.value == SyncStatus.Syncing }
                assertTrue(probe!!.invoke())
                delay(150.milliseconds) // initialDelay 20 мс прошёл, но синхронизация идёт
                assertEquals(0, manager.checks.get())
                gate.complete(Unit)
                eventually { !probe!!.invoke() }
                eventually { manager.checks.get() >= 1 }
            } finally {
                gate.complete(Unit) // иначе close() зависнет на заблокированном прогоне
            }
        }
    }
}
