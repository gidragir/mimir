package dev.mimir.drive

import dev.mimir.core.RemoteStorage
import dev.mimir.core.RemoteStorageProvider
import dev.mimir.core.StorageCapabilities
import dev.mimir.drive.auth.GoogleAuthClient
import dev.mimir.drive.auth.InMemoryTokenStorage
import dev.mimir.drive.auth.OAuthTokens
import dev.mimir.drive.client.DriveHttpClient
import dev.mimir.drive.path.DrivePathResolver
import io.ktor.client.HttpClient

object GoogleDriveStorageProvider : RemoteStorageProvider {
    override val id = "gdrive"
    override val displayName = "Google Drive"
    override val capabilities = StorageCapabilities(changeFeed = true)

    override fun create(config: Map<String, String>): RemoteStorage {
        val clientId = config["clientId"]
        val token = config["accessToken"]
        if (clientId != null && token != null) {
            val httpClient = HttpClient()
            val authClient = GoogleAuthClient(httpClient, clientId, config["clientSecret"])
            val storage = InMemoryTokenStorage(
                OAuthTokens(
                    accessToken = token,
                    refreshToken = config["refreshToken"],
                    expiresAtEpochSeconds = config["expiresAt"]?.toLongOrNull() ?: 2000000000L
                )
            )
            val driveClient = DriveHttpClient(httpClient, storage, authClient)
            val resolver = DrivePathResolver(driveClient, config["rootFolder"] ?: "Mimir")
            return GoogleDriveStorage(driveClient, resolver)
        }
        return GoogleDriveStorage()
    }
}
