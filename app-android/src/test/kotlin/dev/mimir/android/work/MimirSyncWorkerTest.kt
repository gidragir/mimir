package dev.mimir.android.work

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MimirSyncWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeSyncRunner : SyncRunner {
        var callCount = 0
        var shouldThrow = false

        override suspend fun syncOnce(): Boolean {
            callCount++
            if (shouldThrow) throw IllegalStateException("sync error")
            return true
        }
    }

    private fun createFactory(runner: SyncRunner): WorkerFactory {
        return object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? {
                return if (workerClassName == MimirSyncWorker::class.java.name) {
                    MimirSyncWorker(appContext, workerParameters, runner)
                } else null
            }
        }
    }

    @Test
    fun `success after sync`() = runTest {
        val runner = FakeSyncRunner()
        val worker = TestListenableWorkerBuilder<MimirSyncWorker>(context)
            .setWorkerFactory(createFactory(runner))
            .build()

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, runner.callCount)
    }

    @Test
    fun `retry on exception below three attempts`() = runTest {
        val runner = FakeSyncRunner().apply { shouldThrow = true }
        val worker = TestListenableWorkerBuilder<MimirSyncWorker>(context)
            .setWorkerFactory(createFactory(runner))
            .setRunAttemptCount(2)
            .build()

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals(1, runner.callCount)
    }

    @Test
    fun `failure after three attempts`() = runTest {
        val runner = FakeSyncRunner().apply { shouldThrow = true }
        val worker = TestListenableWorkerBuilder<MimirSyncWorker>(context)
            .setWorkerFactory(createFactory(runner))
            .setRunAttemptCount(3)
            .build()

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.failure(), result)
        assertEquals(1, runner.callCount)
    }
}
