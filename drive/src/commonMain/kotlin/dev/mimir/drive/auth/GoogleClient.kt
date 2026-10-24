package dev.mimir.drive.auth

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
}
