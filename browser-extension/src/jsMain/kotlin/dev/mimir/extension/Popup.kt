package dev.mimir.extension

import dev.mimir.core.RemoteStorage
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
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.browser.document
import kotlinx.browser.window
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
}

private fun googleClient(http: HttpClient, kind: GoogleClientKind, id: String, secret: String): GoogleAuthClient? =
    if (id.isBlank()) null else GoogleAuthClient(http, GoogleClientCredentials(kind, id, secret.ifBlank { null }))
