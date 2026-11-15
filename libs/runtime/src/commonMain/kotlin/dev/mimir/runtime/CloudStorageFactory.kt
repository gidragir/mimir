package dev.mimir.runtime

import dev.mimir.core.ChangeFeedStorage
import dev.mimir.core.SyncMetrics
import dev.mimir.drive.GoogleDriveStorage
import dev.mimir.drive.auth.AccessTokenSource
import dev.mimir.drive.client.DriveHttpClient
import dev.mimir.drive.path.DrivePathResolver
import io.ktor.client.HttpClient

/** Облако без шифрования поверх токенов активного способа входа; `null` — облако не настроено. */
fun interface CloudStorageFactory {
    fun create(httpClient: HttpClient, tokenSource: AccessTokenSource, config: RuntimeConfig): ChangeFeedStorage?

    fun create(
        httpClient: HttpClient,
        tokenSource: AccessTokenSource,
        config: RuntimeConfig,
        metrics: SyncMetrics,
    ): ChangeFeedStorage? = create(httpClient, tokenSource, config)
}

val googleDriveFactory: CloudStorageFactory = object : CloudStorageFactory {
    override fun create(httpClient: HttpClient, tokenSource: AccessTokenSource, config: RuntimeConfig): ChangeFeedStorage? =
        create(httpClient, tokenSource, config, SyncMetrics.None)

    override fun create(
        httpClient: HttpClient,
        tokenSource: AccessTokenSource,
        config: RuntimeConfig,
        metrics: SyncMetrics,
    ): ChangeFeedStorage? {
        val driveClient = DriveHttpClient(httpClient, tokenSource, metrics = metrics)
        return GoogleDriveStorage(driveClient, DrivePathResolver(driveClient, config.rootFolder))
    }
}
