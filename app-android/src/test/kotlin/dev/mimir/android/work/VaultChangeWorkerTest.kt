package dev.mimir.android.work

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.mimir.core.rules.SyncRules
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VaultChangeWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
    }

    private class RecordingSyncRunner(
        val onSync: () -> Unit = {},
        val shouldThrow: Boolean = false,
    ) : SyncRunner {
        var callCount = 0

        override suspend fun syncOnce(): Boolean {
            callCount++
            onSync()
            if (shouldThrow) throw IllegalStateException("sync error")
            return true
        }
    }

    private fun createFactory(
        runner: SyncRunner,
        scheduler: SyncWorkScheduler,
        resolver: TriggeredPathResolver,
        vault: () -> VaultContext?,
    ): WorkerFactory = object : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? {
            return if (workerClassName == VaultChangeWorker::class.java.name) {
                VaultChangeWorker(
                    appContext,
                    workerParameters,
                    runner = runner,
                    scheduler = scheduler,
                    resolver = resolver,
                    vault = vault,
                )
            } else null
        }
    }

    @Test
    fun `reschedules trigger before syncing`() = runTest {
        val executionOrder = mutableListOf<String>()
        val scheduler = object : SyncWorkScheduler(workManager) {
            override fun scheduleVaultTrigger() {
                executionOrder.add("scheduleVaultTrigger")
            }
        }
        val runner = RecordingSyncRunner(onSync = {
            executionOrder.add("syncOnce")
        })
        val testUri = Uri.parse("content://media/external/file/101")
        val resolver = TriggeredPathResolver { listOf("/storage/emulated/0/Vault/notes/todo.md") }
        val vaultContext = VaultContext(
            root = "/storage/emulated/0/Vault",
            rules = SyncRules(),
            writtenBySync = emptySet(),
            storageAccess = true,
        )

        val worker = TestListenableWorkerBuilder<VaultChangeWorker>(context)
            .setTriggeredContentUris(listOf(testUri))
            .setWorkerFactory(createFactory(runner, scheduler, resolver) { vaultContext })
            .build()

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf("scheduleVaultTrigger", "syncOnce"), executionOrder)
    }

    @Test
    fun `syncs when a relevant path changed`() = runTest {
        val scheduler = SyncWorkScheduler(workManager)
        val runner = RecordingSyncRunner()
        val testUri = Uri.parse("content://media/external/file/102")
        val resolver = TriggeredPathResolver { listOf("/storage/emulated/0/Vault/notes/daily.md") }
        val vaultContext = VaultContext(
            root = "/storage/emulated/0/Vault",
            rules = SyncRules(),
            writtenBySync = emptySet(),
            storageAccess = true,
        )

        val worker = TestListenableWorkerBuilder<VaultChangeWorker>(context)
            .setTriggeredContentUris(listOf(testUri))
            .setWorkerFactory(createFactory(runner, scheduler, resolver) { vaultContext })
            .build()

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, runner.callCount)
    }

    @Test
    fun `worker returns success without sync when storage access is missing`() = runTest {
        val scheduler = SyncWorkScheduler(workManager)
        val runner = RecordingSyncRunner()
        val testUri = Uri.parse("content://media/external/file/103")
        val resolver = TriggeredPathResolver { listOf("/storage/emulated/0/Vault/notes/daily.md") }
        val vaultContext = VaultContext(
            root = "/storage/emulated/0/Vault",
            rules = SyncRules(),
            writtenBySync = emptySet(),
            storageAccess = false,
        )

        val worker = TestListenableWorkerBuilder<VaultChangeWorker>(context)
            .setTriggeredContentUris(listOf(testUri))
            .setWorkerFactory(createFactory(runner, scheduler, resolver) { vaultContext })
            .build()

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, runner.callCount)
    }

    @Test
    fun `worker returns success without sync when changed path is not relevant`() = runTest {
        val scheduler = SyncWorkScheduler(workManager)
        val runner = RecordingSyncRunner()
        val testUri = Uri.parse("content://media/external/file/104")
        val resolver = TriggeredPathResolver { listOf("/storage/emulated/0/Other/photo.jpg") }
        val vaultContext = VaultContext(
            root = "/storage/emulated/0/Vault",
            rules = SyncRules(),
            writtenBySync = emptySet(),
            storageAccess = true,
        )

        val worker = TestListenableWorkerBuilder<VaultChangeWorker>(context)
            .setTriggeredContentUris(listOf(testUri))
            .setWorkerFactory(createFactory(runner, scheduler, resolver) { vaultContext })
            .build()

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, runner.callCount)
    }

    @Test
    fun `worker returns success without sync when vault is not configured`() = runTest {
        val scheduler = SyncWorkScheduler(workManager)
        val runner = RecordingSyncRunner()
        val testUri = Uri.parse("content://media/external/file/105")
        val resolver = TriggeredPathResolver { listOf("/storage/emulated/0/Vault/notes/daily.md") }

        val worker = TestListenableWorkerBuilder<VaultChangeWorker>(context)
            .setTriggeredContentUris(listOf(testUri))
            .setWorkerFactory(createFactory(runner, scheduler, resolver) { null })
            .build()

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, runner.callCount)
    }

    @Test
    fun `worker retries on failure below three attempts`() = runTest {
        val scheduler = SyncWorkScheduler(workManager)
        val runner = RecordingSyncRunner(shouldThrow = true)
        val testUri = Uri.parse("content://media/external/file/106")
        val resolver = TriggeredPathResolver { listOf("/storage/emulated/0/Vault/notes/daily.md") }
        val vaultContext = VaultContext(
            root = "/storage/emulated/0/Vault",
            rules = SyncRules(),
            writtenBySync = emptySet(),
            storageAccess = true,
        )

        val worker = TestListenableWorkerBuilder<VaultChangeWorker>(context)
            .setTriggeredContentUris(listOf(testUri))
            .setRunAttemptCount(2)
            .setWorkerFactory(createFactory(runner, scheduler, resolver) { vaultContext })
            .build()

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals(1, runner.callCount)
    }

    @Test
    fun `MimirWorkerFactory creates VaultChangeWorker with dependencies`() {
        val factory = MimirWorkerFactory(
            runtime = { throw UnsupportedOperationException("not called during build") },
            scheduler = { SyncWorkScheduler(workManager) },
        )
        val worker = TestListenableWorkerBuilder<VaultChangeWorker>(context)
            .setWorkerFactory(factory)
            .build()
        assertEquals(VaultChangeWorker::class.java, worker.javaClass)
    }

    @Test
    fun `ContentResolverTriggeredPathResolver handles empty and invalid uris gracefully`() {
        val resolver = ContentResolverTriggeredPathResolver(context.contentResolver)
        val empty = resolver.paths(emptyList())
        assertEquals(emptyList(), empty)

        val invalidUri = Uri.parse("content://invalid/not_an_id")
        val invalidPaths = resolver.paths(listOf(invalidUri))
        assertEquals(emptyList(), invalidPaths)
    }
}

