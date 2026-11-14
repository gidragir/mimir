package dev.mimir.android

import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import dev.mimir.core.NetworkKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = MimirApplication::class)
class MainViewModelTest {
    private lateinit var app: MimirApplication

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `backgroundSync updates reactively when vaultPathFlow emits`() = runBlocking {
        val viewModel = MainViewModel(app)
        assertNull(viewModel.model.backgroundSync.value)

        val vaultDir = File(app.filesDir, "VmVault").apply { mkdirs() }
        app.runtime.configureVault(vaultDir.absolutePath)

        withTimeout(5_000) {
            while (viewModel.model.backgroundSync.value == null) {
                delay(20)
            }
        }
        val bg = viewModel.model.backgroundSync.value
        assertNotNull(bg)
        assertTrue(bg.enabled)
    }

    @Test
    fun `backgroundSyncFor marks waitingForWifi on metered network when vault configured`() {
        assertNull(MainViewModel.backgroundSyncFor(null, null, NetworkKind.METERED))
        val syncTime = Instant.fromEpochMilliseconds(42_000)
        val metered = MainViewModel.backgroundSyncFor("/vault", syncTime, NetworkKind.METERED)
        assertEquals(true, metered?.waitingForWifi)
        assertEquals(syncTime, metered?.lastRun)
        val wifi = MainViewModel.backgroundSyncFor("/vault", syncTime, NetworkKind.WIFI)
        assertEquals(false, wifi?.waitingForWifi)
    }
}
