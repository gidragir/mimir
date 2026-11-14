package dev.mimir.drive.auth

import io.ktor.client.HttpClient

enum class GoogleClientKind { Desktop, Device }

data class GoogleClientCredentials(
    val kind: GoogleClientKind,
    val clientId: String,
    val clientSecret: String?,
)

class GoogleAuthClients(
    val desktop: GoogleAuthClient?,
    val device: GoogleAuthClient?,
) {
    fun forKind(kind: GoogleClientKind): GoogleAuthClient? = when (kind) {
        GoogleClientKind.Desktop -> desktop
        GoogleClientKind.Device -> device
    }

    companion object {
        fun fromIds(
            http: HttpClient,
            desktopId: String?,
            desktopSecret: String?,
            deviceId: String?,
            deviceSecret: String?,
        ): GoogleAuthClients {
            fun client(kind: GoogleClientKind, id: String?, secret: String?): GoogleAuthClient? {
                val cleanId = id?.takeIf { it.isNotBlank() } ?: return null
                return GoogleAuthClient(http, GoogleClientCredentials(kind, cleanId, secret?.takeIf { it.isNotBlank() }))
            }
            return GoogleAuthClients(
                desktop = client(GoogleClientKind.Desktop, desktopId, desktopSecret),
                device = client(GoogleClientKind.Device, deviceId, deviceSecret),
            )
        }
    }
}
