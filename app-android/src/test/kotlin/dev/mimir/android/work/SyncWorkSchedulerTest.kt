package dev.mimir.android.work

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SyncWorkSchedulerTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun `periodic work is unique and requires network`() {
        val scheduler = SyncWorkScheduler(workManager, wifiOnly = { false })
        scheduler.schedulePeriodic()

        val workInfos = workManager.getWorkInfosForUniqueWork(SyncWorkScheduler.PERIODIC).get()
        assertEquals(1, workInfos.size)
        val info = workInfos[0]
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
    }

    @Test
    fun `wifiOnly switches constraint to unmetered`() {
        var wifiOnly = false
        val scheduler = SyncWorkScheduler(workManager, wifiOnly = { wifiOnly })

        scheduler.schedulePeriodic()
        var workInfos = workManager.getWorkInfosForUniqueWork(SyncWorkScheduler.PERIODIC).get()
        assertEquals(NetworkType.CONNECTED, workInfos[0].constraints.requiredNetworkType)

        wifiOnly = true
        scheduler.schedulePeriodic()
        workInfos = workManager.getWorkInfosForUniqueWork(SyncWorkScheduler.PERIODIC).get()
        assertEquals(1, workInfos.size)
        assertEquals(NetworkType.UNMETERED, workInfos[0].constraints.requiredNetworkType)
    }

    @Test
    fun `expedited work keeps existing request`() {
        val scheduler = SyncWorkScheduler(workManager, wifiOnly = { false })
        scheduler.scheduleExpedited()

        val firstInfos = workManager.getWorkInfosForUniqueWork(SyncWorkScheduler.EXPEDITED).get()
        assertEquals(1, firstInfos.size)
        val firstId = firstInfos[0].id

        scheduler.scheduleExpedited()
        val secondInfos = workManager.getWorkInfosForUniqueWork(SyncWorkScheduler.EXPEDITED).get()
        assertEquals(1, secondInfos.size)
        val secondId = secondInfos[0].id

        assertEquals(firstId, secondId, "ExistingWorkPolicy.KEEP must preserve the first work request")
    }
}
