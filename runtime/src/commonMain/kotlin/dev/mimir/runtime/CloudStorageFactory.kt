package dev.mimir.runtime

import dev.mimir.core.ChangeFeedStorage
import dev.mimir.drive.GoogleDriveStorage
import dev.mimir.drive.auth.AccessTokenSource
import dev.mimir.drive.client.DriveHttpClient
import dev.mimir.drive.path.DrivePathResolver
import io.ktor.client.HttpClient

/** Облако без шифрования поверх токенов активного способа входа; `null` — облако не настроено. */
fun interface CloudStorageFactory {
    fun create(httpClient: HttpClient, tokenSource: AccessTokenSource, config: RuntimeConfig): ChangeFeedStorage?
}

val googleDriveFactory = CloudStorageFactory { httpClient, tokenSource, config ->
    val driveClient = DriveHttpClient(httpClient, tokenSource)
    GoogleDriveStorage(driveClient, DrivePathResolver(driveClient, config.rootFolder))
}
