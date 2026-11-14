package dev.mimir.extension

import dev.mimir.core.Account
import dev.mimir.core.BuildConfig as CoreBuildConfig
import dev.mimir.core.CanaryVerifier
import dev.mimir.core.InMemoryRemoteStorage
import dev.mimir.core.RcloneCipher
import dev.mimir.core.RemotePath
import dev.mimir.core.RemoteStorage
import dev.mimir.core.update.ConsoleUpdateLogger
import dev.mimir.core.update.DefaultUpdateManager
import dev.mimir.core.update.FetchUpdateHttpClient
import dev.mimir.core.update.GitHubReleasesChannel
import dev.mimir.core.update.SemVer
import dev.mimir.core.update.TargetPlatform
import dev.mimir.drive.GoogleDriveStorage
import dev.mimir.drive.auth.GoogleAuthClient
import dev.mimir.drive.auth.GoogleAuthClients
import dev.mimir.drive.auth.GoogleClientCredentials
import dev.mimir.drive.auth.GoogleClientKind
import dev.mimir.drive.client.DriveHttpClient
import dev.mimir.drive.path.DrivePathResolver
import dev.mimir.extension.platform.ChromeExtensionPlatform
import dev.mimir.extension.platform.ExtensionPlatform
import dev.mimir.extension.platform.ExtensionTokenStorage
import dev.mimir.extension.ui.DeviceCodeUiPrompt
import dev.mimir.extension.ui.ExtensionAuthHandler
import dev.mimir.extension.ui.ExtensionController
import dev.mimir.extension.ui.GoogleExtensionAuthHandler
import dev.mimir.extension.ui.PopupDomView
import dev.mimir.extension.ui.UpdateBannerView
import dev.mimir.extension.update.ExtensionUpdateController
import dev.mimir.extension.update.WebExtensionNotifyApplier
import dev.mimir.extension.update.bindUpdateBadge
import dev.mimir.extension.vault.AccountCodec
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import org.w3c.dom.HTMLElement
import org.w3c.dom.url.URLSearchParams

fun main() {
    try {
        val params = URLSearchParams(window.location.search)
        val isTab = params.get("mode") == "tab"
        val initialPath = params.get("path")
        val mockParam = params.get("mock")

        val hasChrome = js("typeof chrome !== 'undefined' && Boolean(chrome && chrome.storage && chrome.storage.local)") as Boolean

        if (!hasChrome || mockParam != null) {
            runMockPreview(isTab, initialPath, mockParam)
            return
        }

        val platform = ChromeExtensionPlatform()
        val tokenStorage = ExtensionTokenStorage(platform)
        val http = HttpClient(Js) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val clients = GoogleAuthClients(
            desktop = googleClient(http, GoogleClientKind.Desktop, ExtensionBuildConfig.GOOGLE_DESKTOP_CLIENT_ID, ExtensionBuildConfig.GOOGLE_DESKTOP_CLIENT_SECRET),
            device = googleClient(http, GoogleClientKind.Device, ExtensionBuildConfig.GOOGLE_DEVICE_CLIENT_ID, ExtensionBuildConfig.GOOGLE_DEVICE_CLIENT_SECRET),
        )
        val remoteStorageFactory: suspend () -> RemoteStorage = {
            val driveClient = DriveHttpClient(http, tokenStorage, clients)
            val rootFolder = platform.getLocalString("settings.rootFolder") ?: ExtensionBuildConfig.ROOT_FOLDER
            GoogleDriveStorage(driveClient, DrivePathResolver(driveClient, rootFolder))
        }
        val controller = ExtensionController(
            platform = platform,
            remoteStorageFactory = remoteStorageFactory,
            authHandler = GoogleExtensionAuthHandler(platform, clients, tokenStorage),
        )

        val root = document.getElementById("app") as HTMLElement
        val view = PopupDomView(root, controller, platform, isFullTabMode = isTab)
        val scope = MainScope()
        scope.launch { controller.state.collect { view.render(it) } }
        scope.launch { controller.initialize(initialPath) }
        startUpdateChecks(platform, scope)
    } catch (e: dynamic) {
        val msg = if (e != null && e.stack != null) e.stack else e?.toString() ?: "unknown error"
        console.error("POPUP MAIN ERROR:", msg)
        val root = document.getElementById("app")
        if (root != null) {
            root.innerHTML = "<pre style='color:red;padding:16px;white-space:pre-wrap;'>" + msg + "</pre>"
        }
    }
}

/** Автономная проверка обновлений (без демона): не чаще раза в сутки, значок на иконке и строка над экраном. */
private fun startUpdateChecks(platform: ChromeExtensionPlatform, scope: CoroutineScope) {
    val bannerRoot = document.getElementById("update-banner") as? HTMLElement ?: return
    val currentVersion = SemVer.parse(CoreBuildConfig.VERSION)
    val manager = DefaultUpdateManager(
        currentVersion = currentVersion,
        targetPlatform = TargetPlatform.BROWSER_EXTENSION_ANY,
        channel = GitHubReleasesChannel(CoreBuildConfig.GITHUB_OWNER, CoreBuildConfig.GITHUB_REPO, FetchUpdateHttpClient(), ConsoleUpdateLogger),
        applier = WebExtensionNotifyApplier(platform),
        logger = ConsoleUpdateLogger,
    )
    val updates = ExtensionUpdateController(manager, platform, currentVersion)
    val banner = UpdateBannerView(bannerRoot, updates, scope)
    scope.launch { updates.state.collect { banner.render(it) } }
    scope.launch { bindUpdateBadge(updates.state, platform) }
    scope.launch { updates.checkIfDue() }
}

private fun runMockPreview(isTab: Boolean, initialPath: String?, mockMode: String?) {
    val platform = PreviewExtensionPlatform()
    val scope = MainScope()

    if (mockMode == "auth") {
        val controller = ExtensionController(
            platform = platform,
            remoteStorageFactory = { InMemoryRemoteStorage() },
            authHandler = PreviewExtensionAuthHandler(platform),
        )
        val root = document.getElementById("app") as HTMLElement
        val view = PopupDomView(root, controller, platform, isFullTabMode = isTab)
        scope.launch { controller.state.collect { view.render(it) } }
        scope.launch { controller.initialize() }
        return
    }

    val account = Account(email = "user@mimir.io", displayName = "Obsidian User")
    platform.seedAccount(account)

    if (mockMode == "unlock") {
        val controller = ExtensionController(
            platform = platform,
            remoteStorageFactory = { InMemoryRemoteStorage() },
            authHandler = PreviewExtensionAuthHandler(platform),
        )
        val root = document.getElementById("app") as HTMLElement
        val view = PopupDomView(root, controller, platform, isFullTabMode = isTab)
        scope.launch { controller.state.collect { view.render(it) } }
        scope.launch { controller.initialize() }
        return
    }

    scope.launch {
        val cipher = RcloneCipher.create("preview-password")
        val remote = InMemoryRemoteStorage()
        remote.write(
            RemotePath(CanaryVerifier.CANARY_FILENAME),
            cipher.encrypt(Buffer().apply { writeString(CanaryVerifier.CANARY_BODY) }),
        )
        remote.write(
            RemotePath.of(listOf("Добро пожаловать.md").map(cipher::encryptName)),
            cipher.encrypt(Buffer().apply {
                writeString(
                    """
                    # Mimir Quick View
                    
                    Быстрый просмотр ваших заметок Obsidian прямо в браузере.
                    
                    - **Шифрование:** rclone crypt
                    - **Хранилище:** Google Drive
                    - **Режимы:** Side Panel и вкладка на два столбца
                    
                    Все заметки синхронизируются мгновенно и безопасно.
                    """.trimIndent(),
                )
            }),
        )
        remote.write(
            RemotePath.of(listOf("Проекты", "Архитектура.md").map(cipher::encryptName)),
            cipher.encrypt(Buffer().apply { writeString("# Архитектура Mimir\n\nМодульный клиент синхронизации.") }),
        )
        remote.write(
            RemotePath.of(listOf("Ежедневник", "2026-11-03.md").map(cipher::encryptName)),
            cipher.encrypt(Buffer().apply { writeString("# 2026-11-03\n\n- [x] Визуальная проверка UI\n- [ ] Синхронизация хранилища") }),
        )

        val controller = ExtensionController(
            platform = platform,
            remoteStorageFactory = { remote },
            authHandler = PreviewExtensionAuthHandler(platform),
        )

        val root = document.getElementById("app") as HTMLElement
        val view = PopupDomView(root, controller, platform, isFullTabMode = isTab)
        launch { controller.state.collect { view.render(it) } }
        controller.initialize(initialPath ?: if (isTab) "Добро пожаловать.md" else null)
        controller.unlock("preview-password")
    }
}

private class PreviewExtensionPlatform : ExtensionPlatform {
    private val session = mutableMapOf<String, String>()
    private val local = mutableMapOf<String, String>()

    fun seedAccount(account: Account) {
        local[AccountCodec.KEY] = AccountCodec.encode(account)
    }

    override fun observeSessionKey(key: String, onChanged: (String?) -> Unit) {}
    override suspend fun getSessionString(key: String): String? = session[key]
    override suspend fun setSessionString(key: String, value: String) { session[key] = value }
    override suspend fun removeSessionKey(key: String) { session.remove(key) }
    override suspend fun clearSession() = session.clear()
    override suspend fun getLocalString(key: String): String? = local[key]
    override suspend fun setLocalString(key: String, value: String) { local[key] = value }
    override suspend fun removeLocalKey(key: String) { local.remove(key) }
    override suspend fun clearLocal() = local.clear()
    override fun getRedirectUrl(path: String): String = "https://preview.local/$path"
    override suspend fun launchWebAuthFlow(authUrl: String, interactive: Boolean): String = ""
    override suspend fun launchLoopbackAuthFlow(authUrl: String, redirectPrefix: String): String = ""
    override fun openFullTab(relativePath: String?) {}
    override fun openExternalUrl(url: String) {}
    override suspend fun copyToClipboard(text: String) {}
    override fun setBadge(text: String) {}
}

private class PreviewExtensionAuthHandler(
    private val platform: ExtensionPlatform,
) : ExtensionAuthHandler {
    private val previewAccount = Account(email = "user@mimir.io", displayName = "Obsidian User")

    override suspend fun currentAccount(): Account? = AccountCodec.load(platform)

    override suspend fun signInWithWebAuthFlow(): Account = previewAccount.also { AccountCodec.save(platform, it) }

    override suspend fun startDeviceCode(onPrompt: (DeviceCodeUiPrompt) -> Unit): Account {
        onPrompt(DeviceCodeUiPrompt(userCode = "ABCD-EFGH", verificationUrl = "https://www.google.com/device"))
        return previewAccount.also { AccountCodec.save(platform, it) }
    }

    override suspend fun signOut() {
        platform.removeLocalKey(AccountCodec.KEY)
    }
}

private fun googleClient(http: HttpClient, kind: GoogleClientKind, id: String, secret: String): GoogleAuthClient? =
    if (id.isBlank()) null else GoogleAuthClient(http, GoogleClientCredentials(kind, id, secret.ifBlank { null }))
