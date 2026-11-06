package dev.mimir.android

import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.mimir.android.work.SyncWorkScheduler
import dev.mimir.client.wireName
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
    fun `statusNotifier is available and updateNotification handles not configured without error`() {
        kotlin.test.assertNotNull(app.statusNotifier)
        app.updateNotification()
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

    @Test
    fun `configuring vault after onCreate schedules vault trigger and periodic sync without restart`() = runBlocking {
        val vaultDir = File(app.filesDir, "WizardVault").apply { mkdirs() }
        app.runtime.configureVault(vaultDir.absolutePath)
        kotlinx.coroutines.delay(100)

        val triggerWorks = workManager.getWorkInfosForUniqueWork(SyncWorkScheduler.VAULT_CHANGE).get()
        val periodicWorks = workManager.getWorkInfosForUniqueWork(SyncWorkScheduler.PERIODIC).get()

        assertEquals(1, triggerWorks.size)
        assertEquals(1, periodicWorks.size)
    }

    @Test
    fun `PlayServicesGisAuthorizer falls back to account email and caches it in prefs`() {
        val prefs = app.getSharedPreferences("test_gis_fallback", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val authorizer = dev.mimir.android.auth.PlayServicesGisAuthorizer(
            context = app,
            accountPrefs = prefs,
            fallbackAccountEmail = { "saved@example.com" },
        )

        kotlin.test.assertTrue(authorizer.hasSavedAccount())
        assertEquals("saved@example.com", prefs.getString(dev.mimir.android.auth.PlayServicesGisAuthorizer.PREF_GIS_ACCOUNT, null))
    }

    @Test
    fun `lifecycle observer onStop during active sync cancels runner and schedules expedited work`() = runBlocking {
        val dataDir = File(app.filesDir, "lifecycle-data").apply { mkdirs() }.toPath()
        val vaultDir = File(app.filesDir, "lifecycle-vault").apply { mkdirs() }
        File(vaultDir, "note.md").writeText("hello")
        java.nio.file.Files.writeString(
            dataDir.resolve("account.json"),
            """{"email":"user@example.com","method":"${dev.mimir.client.DaemonLoginMethod.SystemAccount.wireName}"}""",
        )
        java.nio.file.Files.writeString(dataDir.resolve("index.json"), """{"version":1,"records":[]}""")

        val listGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val baseRemote = dev.mimir.core.InMemoryRemoteStorage()
        val gatedRemote = object : dev.mimir.core.ChangeFeedStorage by baseRemote {
            override suspend fun listAll(
                root: dev.mimir.core.RemotePath,
                onProgress: (folders: Int, files: Int) -> Unit,
            ): List<dev.mimir.core.RemoteEntry> {
                listGate.await()
                return baseRemote.listAll(root, onProgress)
            }
        }
        val provider = object : dev.mimir.runtime.login.LoginProvider {
            override val method = dev.mimir.client.DaemonLoginMethod.SystemAccount
            override val tokenSource = object : dev.mimir.drive.auth.AccessTokenSource {
                override suspend fun token() = "tok"
                override suspend fun refresh() = "tok"
                override suspend fun hasCredentials() = true
            }
            override suspend fun start() = dev.mimir.client.DaemonLoginStart.Started
            override suspend fun poll() = dev.mimir.client.DaemonLoginPoll.Authorized("user@example.com")
            override suspend fun signOut() = Unit
        }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val settings = dev.mimir.runtime.JsonRuntimeSettingsStore(dataDir).apply {
            saveVaultPath(vaultDir.absolutePath)
        }
        val rt = dev.mimir.runtime.MimirRuntime(
            settings = settings,
            logins = dev.mimir.runtime.login.LoginRegistry(listOf(provider)),
            watcherFactory = dev.mimir.runtime.VaultWatcherFactory { _, _, _ ->
                object : dev.mimir.core.fs.VaultWatcher {
                    override fun start() = kotlinx.coroutines.flow.emptyFlow<dev.mimir.core.VaultChange>()
                    override fun stop() = Unit
                    override fun markWrittenBySync(relativePath: String) = Unit
                }
            },
            passwordProvider = { "secret" },
            ioDispatcher = kotlinx.coroutines.Dispatchers.IO,
            httpClient = AndroidRuntimeFactory.defaultHttpClient(),
            cloudFactory = { _, _, _ -> gatedRemote },
            scope = scope,
            initialInteractive = false,
        )
        try {
            val observer = MimirAppLifecycleObserver(
                runtime = { rt },
                workScheduler = { app.workScheduler },
                scope = scope,
            )
            val dummyOwner = object : androidx.lifecycle.LifecycleOwner {
                override val lifecycle: androidx.lifecycle.Lifecycle
                    get() = androidx.lifecycle.LifecycleRegistry.createUnsafe(this)
            }

            observer.onStart(dummyOwner)
            kotlinx.coroutines.withTimeout(5_000) {
                while (rt.status.value != dev.mimir.core.SyncStatus.Syncing) {
                    kotlinx.coroutines.delay(20)
                }
            }

            observer.onStop(dummyOwner)
            kotlinx.coroutines.withTimeout(5_000) {
                while (rt.status.value != dev.mimir.core.SyncStatus.Idle) {
                    kotlinx.coroutines.delay(20)
                }
            }

            val expeditedWorks = workManager.getWorkInfosForUniqueWork(SyncWorkScheduler.EXPEDITED).get()
            assertEquals(1, expeditedWorks.size, "interrupted sync on onStop must hand off to WorkManager")
        } finally {
            rt.close()
        }
    }
}
