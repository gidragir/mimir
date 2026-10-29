package dev.mimir.android

import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.mimir.android.work.SyncWorkScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = MimirApplication::class)
class MimirApplicationTest {
    private lateinit var app: MimirApplication
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        workManager = WorkManager.getInstance(app)
    }

    @Test
    fun `application initializes without crash`() {
        assertEquals(app, ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `when vault is configured schedules vault trigger and periodic sync`() {
        val vaultDir = File(app.filesDir, "TestVault").apply { mkdirs() }
        runBlocking {
            app.runtime.configureVault(vaultDir.absolutePath)
        }

        app.onCreate()

        val triggerWorks = workManager.getWorkInfosForUniqueWork(SyncWorkScheduler.VAULT_CHANGE).get()
        val periodicWorks = workManager.getWorkInfosForUniqueWork(SyncWorkScheduler.PERIODIC).get()

        assertEquals(1, triggerWorks.size)
        assertEquals(1, periodicWorks.size)
    }
}
