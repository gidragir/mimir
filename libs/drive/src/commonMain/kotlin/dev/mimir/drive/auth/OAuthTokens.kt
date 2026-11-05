package dev.mimir.drive.auth

import kotlin.time.Clock
import kotlinx.serialization.Serializable

@Serializable
data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochSeconds: Long,
    val scope: String = "",
    val tokenType: String = "Bearer",
    val clientKind: GoogleClientKind = GoogleClientKind.Device,
) {
    fun isExpired(clock: Clock = Clock.System, skewSeconds: Long = 60): Boolean =
        clock.now().epochSeconds >= (expiresAtEpochSeconds - skewSeconds)
}
