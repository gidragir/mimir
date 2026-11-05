package dev.mimir.android

import android.content.Context
import dev.mimir.android.auth.GisTokenSource
import dev.mimir.android.auth.PlayServicesGisAuthorizer
import dev.mimir.android.auth.SystemAccountLoginProvider
import dev.mimir.android.update.AndroidApkInstallLauncher
import dev.mimir.core.AndroidKeystoreSecretStore
import dev.mimir.core.LocalVault
import dev.mimir.core.fs.AndroidFileObserverVaultWatcher
import dev.mimir.core.fs.FileSystemLocalVault
import dev.mimir.core.update.TargetPlatform
import dev.mimir.drive.auth.GoogleAuthClient
import dev.mimir.drive.auth.GoogleAuthClients
import dev.mimir.drive.auth.GoogleClientCredentials
import dev.mimir.drive.auth.GoogleClientKind
import dev.mimir.drive.auth.RefreshingTokenSource
import dev.mimir.drive.auth.SecretStoreTokenStorage
import dev.mimir.runtime.JsonRuntimeSettingsStore
import dev.mimir.runtime.MimirRuntime
import dev.mimir.runtime.VaultWatcherFactory
import dev.mimir.runtime.login.BrowserLoginProvider
import dev.mimir.runtime.login.LoginProvider
import dev.mimir.runtime.login.LoginRegistry
import dev.mimir.runtime.update.ApkInstallApplier
import dev.mimir.runtime.update.KtorUpdateHttpClient
import dev.mimir.runtime.update.UpdateQuiesce
import dev.mimir.runtime.update.VerifiedFileDownloader
import dev.mimir.runtime.update.createGitHubUpdateManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import kotlinx.io.files.Path as KxPath

object AndroidRuntimeFactory {

    fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    fun create(
        context: Context,
        httpClient: HttpClient = defaultHttpClient(),
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ): MimirRuntime {
        val appContext = context.applicationContext ?: context
        val dataDir = appContext.filesDir.toPath()
        val secretStore = AndroidKeystoreSecretStore(appContext)
        val tokenStorage = SecretStoreTokenStorage(secretStore)

        val desktopClientId = BuildConfig.GOOGLE_DESKTOP_CLIENT_ID
        val desktopClientSecret = BuildConfig.GOOGLE_DESKTOP_CLIENT_SECRET.takeIf { it.isNotEmpty() }
        val desktopCredentials = if (desktopClientId.isNotEmpty()) {
            GoogleClientCredentials(GoogleClientKind.Desktop, desktopClientId, desktopClientSecret)
        } else null

        val desktopAuthClient = desktopCredentials?.let { GoogleAuthClient(httpClient, it) }
        val emailFetcherClient = desktopAuthClient ?: GoogleAuthClient(
            httpClient,
            GoogleClientCredentials(GoogleClientKind.Desktop, "anonymous", null),
        )

        val accountPrefs = appContext.getSharedPreferences("mimir_gis", Context.MODE_PRIVATE)
        val authorizer = PlayServicesGisAuthorizer(appContext, accountPrefs)

        val providers = mutableListOf<LoginProvider>()
        if (authorizer.isAvailable()) {
            val gisTokenSource = GisTokenSource(authorizer)
            providers.add(
                SystemAccountLoginProvider(gisTokenSource) { token ->
                    try {
                        emailFetcherClient.fetchAccount(token).email.takeIf { it.isNotEmpty() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                },
            )
        }

        if (desktopAuthClient != null) {
            val tokens = RefreshingTokenSource(tokenStorage, GoogleAuthClients(desktop = desktopAuthClient, device = null))
            providers.add(
                BrowserLoginProvider(
                    client = desktopAuthClient,
                    tokenStorage = tokenStorage,
                    scope = scope,
                    ioDispatcher = Dispatchers.IO,
                    tokenSource = tokens,
                ),
            )
        }

        val watcherFactory = VaultWatcherFactory { root, rules, debounce ->
            AndroidFileObserverVaultWatcher(File(root.toString()), rules, debounce)
        }

        // Установщик создаётся раньше рантайма, а останавливать синхронизацию должен именно рантайм.
        var runtimeRef: MimirRuntime? = null
        val quiesce = object : UpdateQuiesce {
            override suspend fun quiesce(): Boolean = runtimeRef?.quiesceForUpdate() ?: false
            override suspend fun resume() { runtimeRef?.resumeAfterQuiesce() }
        }
        val updatesDir = appContext.cacheDir.toPath().resolve("updates")
        scope.launch(Dispatchers.IO) { VerifiedFileDownloader.sweepStale(updatesDir, "mimir-update-*.apk") }
        val updateApplier = ApkInstallApplier(
            httpClient = KtorUpdateHttpClient(httpClient),
            updatesDir = updatesDir,
            launcher = AndroidApkInstallLauncher(appContext),
            quiesce = quiesce,
            ioDispatcher = Dispatchers.IO,
            scope = scope,
        )

        return MimirRuntime(
            settings = JsonRuntimeSettingsStore(dataDir),
            logins = LoginRegistry(providers),
            watcherFactory = watcherFactory,
            passwordProvider = { secretStore.get("vault_password") },
            ioDispatcher = Dispatchers.IO,
            httpClient = httpClient,
            scope = scope,
            localVaultFactory = { path: KxPath -> FileSystemLocalVault(path, ioDispatcher = Dispatchers.IO) },
            updateManager = createGitHubUpdateManager(httpClient, TargetPlatform.ANDROID_ANY, updateApplier),
        ).also { runtimeRef = it }
    }
}
