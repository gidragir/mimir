package dev.mimir.extension

import dev.mimir.core.BuildConfig as CoreBuildConfig
import dev.mimir.core.RemoteStorage
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
import dev.mimir.extension.markdown.SafeMarkdownRenderer
import dev.mimir.extension.platform.ChromeExtensionPlatform
import dev.mimir.extension.platform.ExtensionTokenStorage
import dev.mimir.extension.ui.ExtensionController
import dev.mimir.extension.ui.GoogleExtensionAuthHandler
import dev.mimir.extension.ui.PopupDomView
import dev.mimir.extension.ui.UpdateBannerView
import dev.mimir.extension.update.ExtensionUpdateController
import dev.mimir.extension.update.WebExtensionNotifyApplier
import dev.mimir.extension.update.bindUpdateBadge
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.w3c.dom.HTMLElement
import org.w3c.dom.url.URLSearchParams

fun main() {
    val params = URLSearchParams(window.location.search)
    val isTab = params.get("mode") == "tab"
    val initialPath = params.get("path")

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
        plainTextOf = SafeMarkdownRenderer::stripMarkdownToPlainText,
    )

    val root = document.getElementById("app") as HTMLElement
    val view = PopupDomView(root, controller, platform, isFullTabMode = isTab)
    val scope = MainScope()
    scope.launch { controller.state.collect { view.render(it) } }
    scope.launch { controller.initialize(initialPath) }
    startUpdateChecks(platform, scope)
}

/** Автономная проверка обновлений (без демона): не чаще раза в сутки, значок на иконке и строка над экраном. */
private fun startUpdateChecks(platform: ChromeExtensionPlatform, scope: CoroutineScope) {
    val bannerRoot = document.getElementById("update-banner") as? HTMLElement ?: return
    val currentVersion = SemVer.parse(CoreBuildConfig.VERSION)
    val manager = DefaultUpdateManager(
        currentVersion = currentVersion,
        targetPlatform = TargetPlatform.BROWSER_EXTENSION_ANY,
        channel = GitHubReleasesChannel(CoreBuildConfig.GITHUB_OWNER, CoreBuildConfig.GITHUB_REPO, FetchUpdateHttpClient()),
        applier = WebExtensionNotifyApplier(platform),
    )
    val updates = ExtensionUpdateController(manager, platform, currentVersion)
    val banner = UpdateBannerView(bannerRoot, updates, scope)
    scope.launch { updates.state.collect { banner.render(it) } }
    scope.launch { bindUpdateBadge(updates.state, platform) }
    scope.launch { updates.checkIfDue() }
}

private fun googleClient(http: HttpClient, kind: GoogleClientKind, id: String, secret: String): GoogleAuthClient? =
    if (id.isBlank()) null else GoogleAuthClient(http, GoogleClientCredentials(kind, id, secret.ifBlank { null }))
