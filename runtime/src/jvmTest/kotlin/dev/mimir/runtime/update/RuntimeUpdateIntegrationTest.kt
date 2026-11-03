package dev.mimir.runtime.update

import dev.mimir.core.update.DownloadedAsset
import dev.mimir.core.update.SemVer
import dev.mimir.core.update.UpdateApplyResult
import dev.mimir.core.update.UpdateCheckResult
import dev.mimir.core.update.UpdateManager
import dev.mimir.core.update.UpdateState
import dev.mimir.runtime.testRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RuntimeUpdateIntegrationTest {
    private class FakeManager : UpdateManager {
        override val state: StateFlow<UpdateState> = MutableStateFlow(UpdateState.Idle())
        override suspend fun checkForUpdates(force: Boolean): UpdateCheckResult = UpdateCheckResult.NoUpdate(SemVer.parse("1.0.0"))
        override suspend fun downloadUpdate(): Result<DownloadedAsset> = error("unused")
        override suspend fun applyUpdate(): UpdateApplyResult = error("unused")
        override fun dismiss() = Unit
    }

    @Test
    fun defaultsToDisabledManager() = runTest {
        val runtime = testRuntime(Files.createTempDirectory("mimir-upd"))
        try {
            assertSame(DisabledUpdateManager, runtime.updateManager)
            assertTrue(runtime.updateManager.checkForUpdates() is UpdateCheckResult.Error)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun exposesInjectedManagerAndWiresSyncBusyProbe() = runTest {
        val manager = FakeManager()
        var probe: (() -> Boolean)? = null
        val runtime = testRuntime(
            Files.createTempDirectory("mimir-upd"),
            updateManager = manager,
            updateSchedulerFactory = { m, busy ->
                assertSame(manager, m)
                probe = busy
                UpdateScheduler(m, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined + kotlinx.coroutines.Job()), busy)
            },
        )
        try {
            assertSame(manager, runtime.updateManager)
            assertEquals(false, probe!!.invoke()) // NotConfigured — синхронизации нет
        } finally {
            runtime.close()
        }
    }
}
