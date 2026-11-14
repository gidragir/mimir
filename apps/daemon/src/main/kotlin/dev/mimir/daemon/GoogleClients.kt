package dev.mimir.daemon

import dev.mimir.drive.auth.GoogleAuthClients
import dev.mimir.drive.auth.GoogleClientCredentials
import dev.mimir.drive.auth.GoogleClientKind
import io.ktor.client.HttpClient

/** Значения клиентов Google, вшитые при сборке (`MIMIR_BUILD_GOOGLE_*`); пустая строка — не задано. */
open class EmbeddedGoogleClients(
    private val desktopId: String,
    private val desktopSecret: String,
    private val deviceId: String,
    private val deviceSecret: String,
) {
    val desktop: GoogleClientCredentials? get() = credentials(GoogleClientKind.Desktop, desktopId, desktopSecret)
    val device: GoogleClientCredentials? get() = credentials(GoogleClientKind.Device, deviceId, deviceSecret)

    companion object : EmbeddedGoogleClients(
        DaemonBuildConfig.GOOGLE_DESKTOP_CLIENT_ID,
        DaemonBuildConfig.GOOGLE_DESKTOP_CLIENT_SECRET,
        DaemonBuildConfig.GOOGLE_DEVICE_CLIENT_ID,
        DaemonBuildConfig.GOOGLE_DEVICE_CLIENT_SECRET,
    )
}

private fun credentials(kind: GoogleClientKind, id: String?, secret: String?): GoogleClientCredentials? {
    val clientId = id?.takeIf { it.isNotEmpty() } ?: return null
    return GoogleClientCredentials(kind, clientId, secret?.takeIf { it.isNotEmpty() })
}

/** (desktop, device): заданное в конфиге/окружении перекрывает вшитое. */
fun DaemonConfig.googleCredentials(
    embedded: EmbeddedGoogleClients = EmbeddedGoogleClients,
): Pair<GoogleClientCredentials?, GoogleClientCredentials?> {
    val desktop = credentials(GoogleClientKind.Desktop, googleDesktopClientId, googleDesktopClientSecret) ?: embedded.desktop
    val device = credentials(GoogleClientKind.Device, googleDeviceClientId, googleDeviceClientSecret) ?: embedded.device
    return desktop to device
}

internal fun DaemonConfig.authClients(httpClient: HttpClient): GoogleAuthClients {
    val (desktop, device) = googleCredentials()
    return GoogleAuthClients.fromIds(
        httpClient,
        desktopId = desktop?.clientId,
        desktopSecret = desktop?.clientSecret,
        deviceId = device?.clientId,
        deviceSecret = device?.clientSecret,
    )
}
